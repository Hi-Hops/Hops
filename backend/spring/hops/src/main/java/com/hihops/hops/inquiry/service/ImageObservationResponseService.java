package com.hihops.hops.inquiry.service;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

import com.hihops.hops.global.exception.BusinessException;
import com.hihops.hops.global.exception.ErrorCode;
import com.hihops.hops.global.idempotency.CommandResult;
import com.hihops.hops.global.idempotency.IdempotencyService;
import com.hihops.hops.global.idempotency.IdempotentRequest;
import com.hihops.hops.global.idempotency.ResourceRef;
import com.hihops.hops.global.idempotency.ResourceType;
import com.hihops.hops.inquiry.domain.Inquiry;
import com.hihops.hops.inquiry.domain.InquiryStatus;
import com.hihops.hops.inquiry.dto.ImageObservationResponseCommand;
import com.hihops.hops.inquiry.repository.InquiryRepository;
import com.hihops.hops.inquiry.repository.MessageRepository;
import com.hihops.hops.realtime.dto.MessageStoredEvent;

import lombok.RequiredArgsConstructor;

/** 문의자 확인은 관찰 사실에 대한 응답이다. 티켓·해결 상태를 변경하지 않는다. */
@Service
@RequiredArgsConstructor
public class ImageObservationResponseService {
    private final InquiryRepository inquiries;
    private final IdempotencyService idempotency;
    private final JdbcTemplate jdbc;
    private final NamedParameterJdbcTemplate namedJdbc;
    private final ApplicationEventPublisher events;
    private final MessageRepository messages;
    private final GuideVisibility guides;

    public CommandResult respond(IdempotentRequest request, UUID inquiryId, ImageObservationResponseCommand command) {
        List<UUID> ids = command.responses().stream().map(ImageObservationResponseCommand.Response::observationId).toList();
        if (new HashSet<>(ids).size() != ids.size()) throw new BusinessException(ErrorCode.INVALID_INPUT);
        // 같은 키의 재조회에도 현재 문의 소유권을 먼저 확인한다.
        inquiries.findByIdAndRequester_IdAndDeletedAtIsNull(inquiryId, request.actorId())
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        return idempotency.execute(request, operationId -> {
            apply(request.actorId(), inquiryId, operationId, command, ids);
            return new ResourceRef(ResourceType.INQUIRY, inquiryId);
        });
    }

    private void apply(UUID actorId, UUID inquiryId, UUID operationId, ImageObservationResponseCommand command,
            List<UUID> ids) {
        Inquiry inquiry = inquiries.findByIdAndDeletedAtIsNull(inquiryId)
                .filter(found -> found.getRequester().getId().equals(actorId))
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        if (inquiry.getVersion() != command.expectedInquiryVersion()) {
            throw new BusinessException(ErrorCode.INQUIRY_VERSION_CONFLICT);
        }
        if (inquiry.getInquiryStatus() == InquiryStatus.CLOSED || inquiry.getInquiryStatus() == InquiryStatus.RESOLVED) {
            throw new BusinessException(ErrorCode.INVALID_STATE);
        }
        // 문의자에게 현재 보여줄 수 있는 질문만 잠근다. 오래된 이미지·메시지와 미공개 관찰은 존재를 숨긴다.
        Map<UUID, Row> rows = namedJdbc.query("SELECT o.id, o.observation_revision, o.answer_message_id "
                + ImageObservationVisibility.FROM_WHERE + " AND o.id IN (:ids) ORDER BY o.id FOR UPDATE OF o",
                Map.of("ids", ids, "inquiry", inquiryId, "actor", actorId), (rs, row) ->
                new Row(rs.getObject("id", UUID.class), rs.getInt("observation_revision"),
                        rs.getObject("answer_message_id", UUID.class))).stream()
                .collect(Collectors.toMap(Row::id, row -> row));
        if (rows.size() != ids.size()) throw new BusinessException(ErrorCode.NOT_FOUND);
        Set<UUID> answerIds = rows.values().stream().map(Row::answerId).collect(Collectors.toSet());
        var answerMessages = messages.findAllById(answerIds);
        if (answerMessages.size() != answerIds.size()
                || guides.viewsOf(inquiry, answerMessages).values().stream().anyMatch(GuideVisibility.GuideView::hidden)) {
            throw new BusinessException(ErrorCode.NOT_FOUND);
        }
        for (var response : command.responses()) {
            if (rows.get(response.observationId()).revision() != response.expectedObservationRevision()) {
                throw new BusinessException(ErrorCode.OBSERVATION_VERSION_CONFLICT);
            }
        }
        List<Object[]> updates = command.responses().stream().map(response -> new Object[] {
                response.decision().name(), response.observationId(), response.expectedObservationRevision() }).toList();
        int[] changed = jdbc.batchUpdate("UPDATE image_observation SET observation_state = ?, "
                + "observation_revision = observation_revision + 1, updated_at = now() WHERE id = ? AND observation_revision = ?", updates);
        for (int count : changed) if (count != 1) throw new BusinessException(ErrorCode.OBSERVATION_VERSION_CONFLICT);
        List<Object[]> history = command.responses().stream().map(response -> new Object[] {
                UUID.randomUUID(), response.observationId(), response.expectedObservationRevision() + 1,
                operationId, actorId, response.decision().name() }).toList();
        jdbc.batchUpdate("INSERT INTO image_observation_response (id, observation_id, observation_revision, operation_id, "
                + "actor_member_id, decision) VALUES (?, ?, ?, ?, ?, ?)", history);
        inquiry.recordInput();
        answerIds.forEach(answerId -> events.publishEvent(MessageStoredEvent.of(inquiryId, inquiry.getVersion(), answerId, null)));
    }

    private record Row(UUID id, int revision, UUID answerId) { }
}
