package com.hihops.hops.realtime.service;

import java.io.IOException;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import com.hihops.hops.global.exception.BusinessException;
import com.hihops.hops.global.exception.ErrorCode;
import com.hihops.hops.realtime.dto.RealtimeTarget;
import com.hihops.hops.realtime.dto.StateChangedEvent;

import jakarta.annotation.PreDestroy;

@Service
public class RealtimeService {
    private static final Logger log = LoggerFactory.getLogger(RealtimeService.class);
    private final Map<UUID, Connection> connections = new ConcurrentHashMap<>();
    private final RealtimeAuthorization authorization;
    private final RealtimeProperties properties;
    private final TaskScheduler scheduler;

    public RealtimeService(RealtimeAuthorization authorization, RealtimeProperties properties,
            @Qualifier("realtimeScheduler") TaskScheduler scheduler) {
        this.authorization = authorization;
        this.properties = properties;
        this.scheduler = scheduler;
    }

    public SseEmitter connect(Jwt jwt, RealtimeTarget target, String lastEventId) {
        authorization.check(jwt, target);
        SseEmitter emitter = new SseEmitter(properties.timeout().toMillis());
        Connection connection = new Connection(UUID.randomUUID(), jwt, target, emitter);
        emitter.onCompletion(connection::remove);
        emitter.onTimeout(connection::close);
        emitter.onError(error -> connection.remove());
        // 이벤트 보존소가 없으므로 재생을 가장하지 않고 종료한다. 소비자는 REST 복구 후 새 연결을 연다.
        if (lastEventId != null && !lastEventId.isBlank()) {
            connection.send(SseEmitter.event().comment("REST resync required"));
            connection.close();
            return emitter;
        }
        connections.put(connection.id, connection);
        try {
            connection.heartbeat = scheduler.scheduleWithFixedDelay(connection::heartbeat, properties.heartbeat());
            connection.expiration = scheduler.schedule(connection::close, jwt.getExpiresAt());
        } catch (org.springframework.core.task.TaskRejectedException exception) {
            connection.close();
            throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE);
        }
        if (connection.closed) {
            connection.remove();
        } else {
            connection.send(SseEmitter.event().comment("connected"));
        }
        return emitter;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void stateChanged(StateChangedEvent event) {
        RealtimeTarget target = new RealtimeTarget(event.target().type(), event.target().departmentId());
        connections.values().stream().filter(connection -> connection.target.equals(target))
                .forEach(connection -> {
                    if (connection.authorized()) {
                        connection.send(SseEmitter.event().id(event.eventId().toString())
                                .name("state.changed").data(event));
                    }
                });
    }

    @PreDestroy
    public void shutdown() {
        connections.values().forEach(Connection::close);
    }

    private final class Connection {
        private final UUID id;
        private final Jwt jwt;
        private final RealtimeTarget target;
        private final SseEmitter emitter;
        private volatile boolean closed;
        private volatile ScheduledFuture<?> heartbeat;
        private volatile ScheduledFuture<?> expiration;

        private Connection(UUID id, Jwt jwt, RealtimeTarget target, SseEmitter emitter) {
            this.id = id;
            this.jwt = jwt;
            this.target = target;
            this.emitter = emitter;
        }

        private boolean authorized() {
            if (closed) {
                return false;
            }
            try {
                authorization.check(jwt, target);
                return true;
            } catch (BusinessException exception) {
                close();
                return false;
            } catch (RuntimeException exception) {
                // DB 장애 때 이전 권한으로 전송하지 않는다. 실패는 서버 오류로 처리한다.
                log.error("SSE 권한 재검사 실패 connectionId={}", id, exception);
                close();
                return false;
            }
        }

        private void heartbeat() {
            if (authorized()) {
                send(SseEmitter.event().comment("heartbeat"));
            }
        }

        private void send(SseEmitter.SseEventBuilder event) {
            if (closed) {
                return;
            }
            try {
                emitter.send(event);
            } catch (IOException | IllegalStateException exception) {
                // 전송 I/O 오류의 Servlet 종료는 컨테이너가 담당한다. 여기서는 등록·예약 작업만 정리한다.
                remove();
            }
        }

        private void remove() {
            closed = true;
            connections.remove(id, this);
            if (heartbeat != null) {
                heartbeat.cancel(false);
            }
            if (expiration != null) {
                expiration.cancel(false);
            }
        }

        private void close() {
            remove();
            emitter.complete();
        }
    }
}
