package com.hihops.hops.ticket.service;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
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
    // 대화가 지나치게 크면 일부만 검사한 뒤 공개하지 않고 요청된 AI 메시지 전체를 숨긴다.
    private static final int MAX_MESSAGES = 5000;
    private static final int MAX_REFERENCES = 50000;
    private static final int MAX_BATCHES = 40;
    private final NamedParameterJdbcTemplate jdbc;

    Set<UUID> restricted(Collection<Message> messages) {
        var ids = messages.stream().filter(message -> message.getSenderType() == SenderType.AI)
                .map(Message::getId).collect(Collectors.toSet());
        if (ids.isEmpty()) return Set.of();
        if (ids.size() > MAX_MESSAGES) return Set.copyOf(ids);
        Set<UUID> visited = new HashSet<>(ids);
        Set<UUID> restricted = new HashSet<>();
        Map<UUID, Set<UUID>> children = new HashMap<>();
        Set<UUID> frontier = ids;
        int referenceCount = 0;
        int batchCount = 0;
        while (!frontier.isEmpty()) {
            var ordered = new ArrayList<>(frontier);
            Set<UUID> next = new HashSet<>();
            for (int offset = 0; offset < ordered.size(); offset += BATCH_SIZE) {
                if (++batchCount > MAX_BATCHES) return Set.copyOf(ids);
                var batch = ordered.subList(offset, Math.min(offset + BATCH_SIZE, ordered.size()));
                restricted.addAll(directlyRestricted(batch));
                int remaining = MAX_REFERENCES - referenceCount;
                var references = references(batch, remaining + 1);
                if (references.size() > remaining) return Set.copyOf(ids);
                referenceCount += references.size();
                for (var reference : references) {
                    if (reference.parent() == null) {
                        restricted.add(reference.child());
                        continue;
                    }
                    children.computeIfAbsent(reference.parent(), ignored -> new HashSet<>()).add(reference.child());
                    if (visited.add(reference.parent())) next.add(reference.parent());
                }
            }
            if (visited.size() > MAX_MESSAGES) return Set.copyOf(ids);
            frontier = next;
        }
        var queue = new ArrayDeque<>(restricted);
        while (!queue.isEmpty()) {
            for (UUID child : children.getOrDefault(queue.removeFirst(), Set.of())) {
                if (restricted.add(child)) queue.addLast(child);
            }
        }
        restricted.retainAll(ids);
        return Set.copyOf(restricted);
    }

    private Set<UUID> directlyRestricted(Collection<UUID> batch) {
        return new HashSet<>(jdbc.queryForList("""
                SELECT m.id FROM message m
                WHERE m.id IN (:ids) AND m.sender_type = 'AI' AND (
                  EXISTS (SELECT 1 FROM image_observation o WHERE o.answer_message_id = m.id)
                  OR (jsonb_exists(m.payload, 'answerRunId') AND NOT EXISTS (
                    SELECT 1 FROM agent_run source
                    WHERE source.id = CASE WHEN m.payload->>'answerRunId' ~
                        '^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$'
                      THEN (m.payload->>'answerRunId')::uuid ELSE NULL END
                      AND source.inquiry_id = m.inquiry_id
                      AND source.task = 'INQUIRY_ANALYSIS'
                      AND source.input_snapshot->>'answerMessageId' = m.id::text
                  ))
                  OR EXISTS (
                    SELECT 1 FROM agent_run r
                    WHERE r.inquiry_id = m.inquiry_id AND r.task = 'INQUIRY_ANALYSIS'
                      AND (r.id::text = m.payload->>'answerRunId'
                           OR r.input_snapshot->>'answerMessageId' = m.id::text)
                      AND (
                        jsonb_typeof(r.input_snapshot) <> 'object'
                        OR (jsonb_exists(r.input_snapshot, 'messages') AND (
                          jsonb_typeof(r.input_snapshot->'messages') <> 'array' OR EXISTS (
                            SELECT 1 FROM jsonb_array_elements(
                              CASE WHEN jsonb_typeof(r.input_snapshot->'messages') = 'array'
                                   THEN r.input_snapshot->'messages' ELSE '[]'::jsonb END
                            ) AS entry(message)
                            WHERE jsonb_typeof(entry.message) <> 'object'
                               OR (jsonb_exists(entry.message, 'imageAttachments')
                                   AND entry.message->'imageAttachments' IS DISTINCT FROM '[]'::jsonb)
                          )
                        ))
                        OR (r.result IS NOT NULL AND (
                          jsonb_typeof(r.result) <> 'object'
                          OR (jsonb_exists(r.result, 'imageObservations')
                              AND r.result->'imageObservations' IS DISTINCT FROM '[]'::jsonb)
                        ))
                      )
                  )
                )
                """, Map.of("ids", batch), UUID.class));
    }

    private List<Reference> references(Collection<UUID> batch, int limit) {
        return jdbc.query("""
                SELECT child.id AS child_id, parent.id AS parent_id
                FROM message child JOIN agent_run r ON r.inquiry_id = child.inquiry_id
                  AND r.task = 'INQUIRY_ANALYSIS'
                  AND (r.id::text = child.payload->>'answerRunId'
                       OR r.input_snapshot->>'answerMessageId' = child.id::text)
                CROSS JOIN LATERAL jsonb_array_elements(
                  CASE WHEN jsonb_typeof(r.input_snapshot->'messages') = 'array'
                       THEN r.input_snapshot->'messages' ELSE '[]'::jsonb END
                ) entry(message)
                LEFT JOIN message parent ON parent.id = CASE WHEN entry.message->>'id' ~
                  '^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$'
                  THEN (entry.message->>'id')::uuid ELSE NULL END
                  AND parent.inquiry_id = child.inquiry_id AND parent.sender_type = 'AI'
                  AND parent.message_status = 'COMPLETED' AND parent.deleted_at IS NULL
                  AND parent.sequence_no < child.sequence_no
                  AND (NOT jsonb_exists(entry.message, 'revision')
                       OR (jsonb_typeof(entry.message->'revision') = 'number'
                           AND entry.message->>'revision' = parent.revision::text))
                WHERE child.id IN (:ids) AND child.sender_type = 'AI' AND entry.message->>'sender' = 'AI'
                LIMIT :limit
                """, Map.of("ids", batch, "limit", limit), (rs, row) ->
                new Reference(rs.getObject("child_id", UUID.class), rs.getObject("parent_id", UUID.class)));
    }

    private record Reference(UUID child, UUID parent) { }
}
