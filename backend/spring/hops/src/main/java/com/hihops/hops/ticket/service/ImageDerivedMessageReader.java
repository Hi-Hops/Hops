package com.hihops.hops.ticket.service;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

import com.hihops.hops.inquiry.domain.Message;
import com.hihops.hops.inquiry.domain.SenderType;

import lombok.RequiredArgsConstructor;

/** 이미지 범위가 승인되기 전에는 그 이미지가 입력된 AI 답변을 담당자·사례에 투영하지 않는다. */
@Component
@RequiredArgsConstructor
class ImageDerivedMessageReader {
    private static final int BATCH_SIZE = 500;
    private final NamedParameterJdbcTemplate jdbc;

    Set<UUID> restricted(Collection<Message> messages) {
        var ids = messages.stream().filter(message -> message.getSenderType() == SenderType.AI)
                .map(Message::getId).collect(Collectors.toSet());
        if (ids.isEmpty()) return Set.of();
        var ordered = new ArrayList<>(ids);
        Set<UUID> restricted = new HashSet<>();
        for (int offset = 0; offset < ordered.size(); offset += BATCH_SIZE) {
            var batch = ordered.subList(offset, Math.min(offset + BATCH_SIZE, ordered.size()));
            restricted.addAll(jdbc.queryForList("""
                SELECT m.id FROM message m
                WHERE m.id IN (:ids) AND m.sender_type = 'AI' AND (
                  EXISTS (SELECT 1 FROM image_observation o WHERE o.answer_message_id = m.id)
                  OR EXISTS (
                    SELECT 1 FROM agent_run r
                    WHERE r.inquiry_id = m.inquiry_id AND r.task = 'INQUIRY_ANALYSIS'
                      AND (r.id::text = m.payload->>'answerRunId'
                           OR r.input_snapshot->>'answerMessageId' = m.id::text)
                      AND jsonb_exists(r.input_snapshot, 'messages')
                      AND (jsonb_typeof(r.input_snapshot->'messages') <> 'array' OR EXISTS (
                        SELECT 1 FROM jsonb_array_elements(
                          CASE WHEN jsonb_typeof(r.input_snapshot->'messages') = 'array'
                               THEN r.input_snapshot->'messages' ELSE '[]'::jsonb END
                        ) AS entry(message)
                        WHERE jsonb_typeof(entry.message) <> 'object'
                           OR (jsonb_exists(entry.message, 'imageAttachments')
                               AND entry.message->'imageAttachments' IS DISTINCT FROM '[]'::jsonb)
                      ))
                  )
                )
                """, Map.of("ids", batch), UUID.class));
        }
        return Set.copyOf(restricted);
    }
}
