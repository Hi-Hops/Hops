package com.hihops.hops.ticket.service;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.Set;

import org.springframework.stereotype.Component;

import com.hihops.hops.inquiry.domain.Message;
import com.hihops.hops.inquiry.domain.MessageRevision;
import com.hihops.hops.ticket.dto.RelatedMessageResponse;
import com.hihops.hops.ticket.dto.RelatedMessageResponse.RevisionResponse;

import lombok.RequiredArgsConstructor;

import tools.jackson.databind.JsonNode;

/** F009-DETAIL-03: 현재 원문과 과거 revision마다 허용된 Unicode 코드 포인트 구간만 공개한다. */
@Component
@RequiredArgsConstructor
public class RelatedMessageProjector {
    private final TicketStoredData data;
    private final ImageDerivedMessageReader imageDerivedMessages;

    /** 각 조회 범위의 이미지 유래 답변을 한 번에 확인한다. */
    public Set<UUID> restricted(Collection<Message> messages) {
        return imageDerivedMessages.restricted(messages);
    }

    public Optional<RelatedMessageResponse> project(Message message, UUID problemId, List<MessageRevision> history,
            Set<UUID> restricted) {
        if (restricted.contains(message.getId())) return Optional.empty();
        JsonNode links = data.parse(message.getPayload()).path("problemLinks");
        Optional<String> content = content(message.getContent(), message.getRevision(), problemId, links);
        if (content.isEmpty()) return Optional.empty();
        List<RevisionResponse> revisions = history.stream()
                .map(old -> content(old.getContent(), old.getRevisionNo(), problemId, links)
                        .map(text -> new RevisionResponse(old.getRevisionNo(), text, old.getCreatedAt())))
                .flatMap(Optional::stream).toList();
        return Optional.of(new RelatedMessageResponse(message.getId(), message.getRevision(), message.getSenderType().name(),
                content.get(), revisions, message.getCreatedAt()));
    }

    private Optional<String> content(String original, int revision, UUID problemId, JsonNode links) {
        if (!links.isArray()) return Optional.empty();
        int length = original.codePointCount(0, original.length());
        List<Span> spans = new ArrayList<>();
        for (JsonNode link : links) {
            if (!problemId.toString().equals(link.path("problemId").asString(null))
                    || !link.path("revision").isIntegralNumber() || !link.path("revision").canConvertToInt()
                    || link.path("revision").asInt(-1) != revision) continue;
            JsonNode allowed = link.path("spans");
            if (!allowed.isArray() || allowed.isEmpty()) return Optional.empty();
            for (JsonNode span : allowed) {
                if (!span.path("start").isIntegralNumber() || !span.path("end").isIntegralNumber()
                        || !span.path("start").canConvertToInt() || !span.path("end").canConvertToInt()) return Optional.empty();
                long start = span.path("start").asLong(-1);
                long end = span.path("end").asLong(-1);
                if (start < 0 || end <= start || end > length) return Optional.empty();
                spans.add(new Span((int) start, (int) end));
            }
        }
        spans.sort(Comparator.comparingInt(Span::start).thenComparingInt(Span::end));
        List<String> parts = new ArrayList<>();
        int previousEnd = -1;
        for (Span span : spans) {
            if (span.start() < previousEnd) return Optional.empty();
            parts.add(original.substring(original.offsetByCodePoints(0, span.start()), original.offsetByCodePoints(0, span.end())));
            previousEnd = span.end();
        }
        if (parts.isEmpty()) return Optional.empty();
        String result = String.join("\n", parts);
        return result.isBlank() ? Optional.empty() : Optional.of(result);
    }

    private record Span(int start, int end) {
    }
}
