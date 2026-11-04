package com.hihops.hops.inquiry.service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import com.hihops.hops.global.idempotency.IdempotencyService;

import lombok.RequiredArgsConstructor;
import tools.jackson.databind.json.JsonMapper;

/**
 * AI 상담 답변을 접수 때 예약한 메시지 ID로 저장한다 (WIRE_CONTRACT 12.8 6번, DATA.md 11.3).
 *
 * <p>AI 입력의 {@code answerMessageId}는 접수 때 미리 발급해 AI와 실행 조회(GET /runs)에 이미 알려 준 값이다. 그런데 Message 엔티티의
 * ID는 Hibernate가 저장할 때 새로 만들어서 미리 정한 ID로는 저장되지 않는다. 모든 엔티티가 쓰는 BaseEntity의 ID 생성을 바꾸지 않으려고
 * 이 메시지만 JDBC로 직접 넣는다. 읽기는 평소처럼 엔티티·Repository가 한다.
 *
 * <p>같은 문의의 메시지 순번이 겹치지 않도록 <b>문의 행을 잠근 거래 안에서</b> 부른다. 호출하는 쪽의 거래에 참여한다.
 */
@Component
@RequiredArgsConstructor
public class AiAnswerMessageWriter {

    private final JdbcTemplate jdbc;
    private final JsonMapper jsonMapper;

    /**
     * @param messageId 접수 때 예약한 answerMessageId
     * @param runId 이 답변을 만든 상담 실행. payload.answerRunId에 남긴다
     * @return 새로 발급한 문의 안의 순번(sequence_no)
     */
    public int insert(UUID inquiryId, UUID messageId, UUID runId, String content) {
        Integer next = jdbc.queryForObject("SELECT COALESCE(MAX(sequence_no), 0) + 1 FROM message WHERE inquiry_id = ?",
                Integer.class, inquiryId);
        int sequenceNo = next == null ? 1 : next;
        jdbc.update("INSERT INTO message (id, inquiry_id, sequence_no, sender_type, content, revision, payload, message_status) "
                + "VALUES (?, ?, ?, 'AI', ?, 1, ?::jsonb, 'COMPLETED')", messageId, inquiryId, sequenceNo, content,
                payload(runId));
        return sequenceNo;
    }

    // DATA.md 11.3의 모든 키를 둔다. 문제 연결·질문 묶음·가이드는 문제 분리를 구현할 때 채운다.
    private String payload(UUID runId) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("contractVersion", IdempotencyService.CONTRACT_VERSION);
        payload.put("requestId", null);
        payload.put("answerRunId", runId);
        payload.put("problemLinks", List.of());
        payload.put("questionBundles", List.of());
        payload.put("guide", null);
        payload.put("pendingTarget", false);
        return jsonMapper.writeValueAsString(payload);
    }
}
