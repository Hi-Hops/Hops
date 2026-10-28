package com.hihops.hops.realtime.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.ScheduledFuture;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.MethodParameter;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

import com.hihops.hops.global.exception.BusinessException;
import com.hihops.hops.global.exception.ErrorCode;
import com.hihops.hops.global.exception.GlobalExceptionHandler;
import com.hihops.hops.realtime.controller.RealtimeController;
import com.hihops.hops.realtime.dto.RealtimeTarget;
import com.hihops.hops.realtime.dto.StateChangedEvent;

class RealtimeServiceTest {
    private final RealtimeAuthorization authorization = mock(RealtimeAuthorization.class);
    private final TaskScheduler scheduler = mock(TaskScheduler.class);
    private final UUID departmentId = UUID.randomUUID();
    private final Jwt jwt = Jwt.withTokenValue("test-token").header("alg", "HS256")
            .subject(UUID.randomUUID().toString()).expiresAt(Instant.now().plusSeconds(60)).build();
    private RealtimeService service;
    private MockMvc mvc;

    @BeforeEach
    void setup() {
        service = new RealtimeService(authorization,
                new RealtimeProperties(Duration.ofSeconds(15), Duration.ofMinutes(5)), scheduler);
        mvc = MockMvcBuilders.standaloneSetup(new RealtimeController(service))
                .setControllerAdvice(new GlobalExceptionHandler())
                .setCustomArgumentResolvers(new HandlerMethodArgumentResolver() {
                    public boolean supportsParameter(MethodParameter parameter) {
                        return parameter.getParameterType() == Jwt.class;
                    }
                    public Object resolveArgument(MethodParameter parameter, ModelAndViewContainer container,
                            NativeWebRequest request, WebDataBinderFactory factory) {
                        return jwt;
                    }
                }).build();
    }

    @Test
    void 허용_연결에만_해당_부서_이벤트를_보낸다() throws Exception {
        MvcResult result = connect(null);
        service.stateChanged(event(UUID.randomUUID()));
        assertThat(result.getResponse().getContentAsString()).doesNotContain("state.changed");
        service.stateChanged(event(departmentId));
        assertThat(result.getResponse().getContentAsString()).contains("event:state.changed", "departmentId");
        service.shutdown();
    }

    @Test
    void 권한이_회수되면_업무_이벤트를_보내지_않고_종료한다() throws Exception {
        MvcResult result = connect(null);
        doThrow(new BusinessException(ErrorCode.ACCESS_DENIED)).when(authorization).check(any(), any());
        service.stateChanged(event(departmentId));
        assertThat(result.getResponse().getContentAsString()).doesNotContain("state.changed");
        assertThat(result.getAsyncResult()).isNull();
    }

    @Test
    void 재생_요청은_가짜_재생_없이_종료한다() throws Exception {
        MvcResult result = connect(UUID.randomUUID().toString());
        assertThat(result.getResponse().getContentAsString()).contains("REST resync required");
        assertThat(result.getAsyncResult()).isNull();
        service.stateChanged(event(departmentId));
        assertThat(result.getResponse().getContentAsString()).doesNotContain("state.changed");
    }

    @Test
    void 만료_예약이_실행되면_연결을_닫는다() throws Exception {
        MvcResult result = connect(null);
        ArgumentCaptor<Runnable> expiration = ArgumentCaptor.forClass(Runnable.class);
        verify(scheduler).schedule(expiration.capture(), org.mockito.ArgumentMatchers.eq(jwt.getExpiresAt()));
        expiration.getValue().run();
        assertThat(result.getAsyncResult()).isNull();
    }

    @Test
    void 잘못된_대상은_스트림을_열기_전에_거부한다() throws Exception {
        mvc.perform(get("/api/realtime/events").param("targetType", "INVALID")
                        .param("targetId", departmentId.toString()))
                .andExpect(status().isBadRequest()).andExpect(request().asyncNotStarted());
    }

    @Test
    void 커밋_전과_롤백에는_이벤트를_전송하지_않는다() throws Exception {
        MvcResult result = connect(null);
        try (var context = new AnnotationConfigApplicationContext()) {
            context.register(TransactionEvents.class);
            context.registerBean(RealtimeService.class, () -> service);
            context.refresh();
            TransactionSynchronizationManager.setActualTransactionActive(true);
            TransactionSynchronizationManager.initSynchronization();
            try {
                context.publishEvent(event(departmentId));
                assertThat(result.getResponse().getContentAsString()).doesNotContain("state.changed");
                var callbacks = TransactionSynchronizationManager
                        .getSynchronizations();
                callbacks.forEach(callback -> callback.afterCompletion(
                        TransactionSynchronization.STATUS_ROLLED_BACK));
                assertThat(result.getResponse().getContentAsString()).doesNotContain("state.changed");
            } finally {
                TransactionSynchronizationManager.clear();
            }
            TransactionSynchronizationManager.setActualTransactionActive(true);
            TransactionSynchronizationManager.initSynchronization();
            try {
                context.publishEvent(event(departmentId));
                TransactionSynchronizationManager.getSynchronizations()
                        .forEach(callback -> callback.afterCompletion(
                                TransactionSynchronization.STATUS_COMMITTED));
                assertThat(result.getResponse().getContentAsString()).contains("event:state.changed");
            } finally {
                TransactionSynchronizationManager.clear();
            }
        }
    }

    @Test
    void 종료하면_heartbeat와_만료_예약을_취소하고_이후_이벤트를_차단한다() throws Exception {
        ScheduledFuture<?> heartbeat = mock(ScheduledFuture.class);
        ScheduledFuture<?> expiration = mock(ScheduledFuture.class);
        when(scheduler.scheduleWithFixedDelay(any(Runnable.class), any(Duration.class)))
                .thenAnswer(invocation -> heartbeat);
        when(scheduler.schedule(any(Runnable.class), any(Instant.class)))
                .thenAnswer(invocation -> expiration);
        MvcResult result = connect(null);
        service.shutdown();
        verify(heartbeat).cancel(false);
        verify(expiration).cancel(false);
        service.stateChanged(event(departmentId));
        assertThat(result.getResponse().getContentAsString()).doesNotContain("state.changed");
    }

    @Test
    void 만료_예약이_거부되면_이미_등록한_heartbeat도_정리한다() {
        ScheduledFuture<?> heartbeat = mock(ScheduledFuture.class);
        when(scheduler.scheduleWithFixedDelay(any(Runnable.class), any(Duration.class)))
                .thenAnswer(invocation -> heartbeat);
        when(scheduler.schedule(any(Runnable.class), any(Instant.class)))
                .thenThrow(new org.springframework.core.task.TaskRejectedException("test rejection"));
        assertThatThrownBy(() -> service.connect(jwt,
                new RealtimeTarget(RealtimeTarget.Type.DEPARTMENT_TICKETS, departmentId), null))
                .isInstanceOfSatisfying(BusinessException.class,
                        error -> assertThat(error.errorCode()).isEqualTo(ErrorCode.SERVICE_UNAVAILABLE));
        verify(heartbeat).cancel(false);
    }

    @Test
    void heartbeat에서도_현재_권한을_확인하고_회수되면_종료한다() throws Exception {
        MvcResult result = connect(null);
        ArgumentCaptor<Runnable> heartbeat = ArgumentCaptor.forClass(Runnable.class);
        verify(scheduler).scheduleWithFixedDelay(heartbeat.capture(), any(Duration.class));
        doThrow(new BusinessException(ErrorCode.ACCESS_DENIED)).when(authorization).check(any(), any());
        heartbeat.getValue().run();
        assertThat(result.getAsyncResult()).isNull();
        assertThat(result.getResponse().getContentAsString()).doesNotContain(":heartbeat");
    }

    @Configuration
    @EnableTransactionManagement
    static class TransactionEvents {
    }

    private MvcResult connect(String cursor) throws Exception {
        var request = get("/api/realtime/events").param("targetType", "DEPARTMENT_TICKETS")
                .param("targetId", departmentId.toString());
        if (cursor != null) {
            request.header("Last-Event-ID", cursor);
        }
        return mvc.perform(request).andExpect(status().isOk()).andExpect(request().asyncStarted()).andReturn();
    }

    private StateChangedEvent event(UUID department) {
        return new StateChangedEvent(UUID.randomUUID(),
                new StateChangedEvent.Target(RealtimeTarget.Type.DEPARTMENT_TICKETS, department), null);
    }
}
