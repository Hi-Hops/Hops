package com.hihops.hops.inquiry.service;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

import com.hihops.hops.inquiry.domain.Message;
import com.hihops.hops.inquiry.privacy.SensitiveTextMasker;

import lombok.RequiredArgsConstructor;

/** 문의자 이력에서 현재 확인 가능한 관찰만 페이지 단위로 읽는다. */
@Component
@ConditionalOnProperty(prefix = "hops.image-attachments", name = "enabled", havingValue = "true")
@RequiredArgsConstructor
public class ImageObservationHistoryReader {
    private final NamedParameterJdbcTemplate jdbc;

    public Map<UUID, List<ObservationQuestion>> forMessages(UUID actorId, UUID inquiryId, Collection<Message> messages,
            Map<UUID, GuideVisibility.GuideView> views) {
        if (messages.isEmpty()) return Map.of();
        Map<UUID, Message> allowed = messages.stream().filter(message -> views.containsKey(message.getId())
                && !views.get(message.getId()).hidden()).collect(Collectors.toMap(Message::getId, message -> message));
        if (allowed.isEmpty()) return Map.of();
        return jdbc.query("SELECT o.answer_message_id, o.id, o.observation_revision, o.attachment_id, "
                + "o.problem_id, o.observation_text, o.observation_state " + ImageObservationVisibility.FROM_WHERE
                + " AND o.answer_message_id IN (:messages) ORDER BY o.created_at, o.id",
                Map.of("actor", actorId, "inquiry", inquiryId, "messages", allowed.keySet()),
                (rs, row) -> new Item(rs.getObject("answer_message_id", UUID.class), new ObservationQuestion(
                        rs.getObject("id", UUID.class), rs.getInt("observation_revision"),
                        rs.getObject("attachment_id", UUID.class), rs.getObject("problem_id", UUID.class),
                        SensitiveTextMasker.mask(rs.getString("observation_text")), rs.getString("observation_state"))))
                .stream().collect(Collectors.groupingBy(Item::messageId,
                        Collectors.mapping(Item::question, Collectors.toList())));
    }

    public record ObservationQuestion(UUID observationId, int observationRevision, UUID attachmentId, UUID problemId,
            String text, String state) { }
    private record Item(UUID messageId, ObservationQuestion question) { }
}
