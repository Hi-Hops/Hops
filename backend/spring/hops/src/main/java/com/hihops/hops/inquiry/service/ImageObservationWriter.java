package com.hihops.hops.inquiry.service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import jakarta.persistence.EntityManager;

import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.hihops.hops.agent.service.AgentResultRejectedException;
import com.hihops.hops.inquiry.privacy.SensitiveTextMasker;

import lombok.RequiredArgsConstructor;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** 결과 적용 거래 안에서 미확인 관찰을 저장한다. 호출자는 문의·실행·정책을 먼저 잠그고 검증한다. */
@Component
@RequiredArgsConstructor
public class ImageObservationWriter {
    private final JdbcTemplate jdbc;
    private final NamedParameterJdbcTemplate namedJdbc;
    private final JsonMapper mapper;
    private final EntityManager entities;
    private final ImageObservationProblemResolver problems;

    /** 같은 결과의 문제 저장 기록을 해석한 뒤 관찰을 저장한다. */
    @Transactional(propagation = Propagation.MANDATORY)
    public int storeResolved(UUID runId, UUID inquiryId, UUID ownerId, UUID answerId, JsonNode input, JsonNode result,
            GuideResultWriter.@Nullable Applied guide, ProblemQuestionWriter.Applied stored) {
        Map<String, UUID> resolved = problems.resolve(runId, inquiryId, input, result, guide, stored);
        return store(runId, inquiryId, ownerId, answerId, input, result, resolved);
    }

    /** 문제 참조는 같은 결과 적용 거래에서 서버가 실제 저장한 문제 ID로 해석해 전달한다. */
    @Transactional(propagation = Propagation.MANDATORY)
    public int store(UUID runId, UUID inquiryId, UUID ownerId, UUID answerId, JsonNode input, JsonNode result,
            Map<String, UUID> resolvedProblems) {
        JsonNode observations = result.path("imageObservations");
        require(observations.isMissingNode() || (observations.isArray() && observations.size() <= 15));
        Map<UUID, Source> sources = sources(input);
        if (sources.isEmpty()) {
            require(observations.isMissingNode() || observations.isEmpty());
            return 0;
        }
        // agent_run.result에도 원문이 기록되므로 관찰뿐 아니라 결과 전체를 검사한다.
        require(!containsSensitiveText(result));
        require(sources.size() <= 5);
        // 질문 메타데이터와 문제는 JPA로 저장될 수 있으므로 JDBC 검사 전에 같은 거래에 반영한다.
        entities.flush();
        Map<UUID, Source> current = new HashMap<>();
        namedJdbc.query("""
                SELECT a.id, a.content_sha256, m.id AS message_id, m.revision
                FROM image_attachment a JOIN message m ON m.id = a.message_id
                WHERE a.id IN (:ids) AND a.attachment_status = 'BOUND' AND a.owner_member_id = :owner
                  AND m.inquiry_id = :inquiry AND m.sender_type = 'USER' AND m.message_status = 'COMPLETED'
                  AND m.deleted_at IS NULL
                """, Map.of("ids", sources.keySet(), "owner", ownerId, "inquiry", inquiryId), rs -> {
            current.put(rs.getObject("id", UUID.class), new Source(rs.getObject("message_id", UUID.class),
                    rs.getInt("revision"), rs.getString("content_sha256")));
        });
        require(current.equals(sources));
        if (observations.isMissingNode() || observations.isEmpty()) return 0;

        Set<UUID> problemIds = new HashSet<>(resolvedProblems.values());
        require(!problemIds.isEmpty() && !problemIds.contains(null));
        List<UUID> existing = namedJdbc.queryForList("SELECT id FROM inquiry_problem WHERE inquiry_id = :inquiry "
                + "AND deleted_at IS NULL AND id IN (:ids)", Map.of("inquiry", inquiryId, "ids", problemIds), UUID.class);
        require(new HashSet<>(existing).equals(problemIds));
        var answerRows = jdbc.query("SELECT content, payload::text FROM message WHERE id = ? AND inquiry_id = ? "
                + "AND sender_type = 'AI' AND message_status = 'COMPLETED' AND deleted_at IS NULL",
                (rs, row) -> new Answer(rs.getString(1), mapper.readTree(rs.getString(2))), answerId, inquiryId);
        require(answerRows.size() == 1);
        Answer answer = answerRows.getFirst();

        Set<String> refs = new HashSet<>();
        Map<UUID, Integer> counts = new HashMap<>();
        List<Object[]> rows = new ArrayList<>();
        for (JsonNode item : observations) {
            require(item.isObject());
            String ref = text(item.path("observationRef"));
            require(ref.matches("[A-Za-z0-9-]{1,64}") && refs.add(ref));
            UUID attachmentId = uuid(item.path("attachmentId"));
            UUID messageId = uuid(item.path("messageId"));
            int revision = positiveInt(item.path("messageRevision"));
            Source source = sources.get(attachmentId);
            require(source != null && source.messageId().equals(messageId) && source.revision() == revision
                    && counts.merge(attachmentId, 1, Integer::sum) <= 3);
            String problemRef = text(item.path("problemRef"));
            UUID problemId = resolvedProblems.get(problemRef);
            require(problemId != null && problemIds.contains(problemId) && proposedProblem(input, result, problemRef));
            String observation = text(item.path("text"));
            require(observation.codePointCount(0, observation.length()) <= 400 && validUnicode(observation));
            JsonNode questionNode = item.path("questionRef");
            require(questionNode.isNull() || questionNode.isString());
            String questionRef = questionNode.isNull() ? null : text(questionNode);
            if (questionRef != null) {
                require(questionRef.length() <= 200 && hasQuestion(result, answer, problemRef, problemId, questionRef, observation));
            }
            rows.add(new Object[] { UUID.randomUUID(), inquiryId, runId, ref, attachmentId, messageId,
                    revision, problemId, answerId, observation, questionRef });
        }
        jdbc.batchUpdate("INSERT INTO image_observation (id, inquiry_id, run_id, observation_ref, attachment_id, "
                + "source_message_id, source_revision, problem_id, answer_message_id, observation_text, question_bundle_ref) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)", rows);
        return rows.size();
    }

    private static Map<UUID, Source> sources(JsonNode input) {
        require(input.path("messages").isArray());
        Map<UUID, Source> found = new HashMap<>();
        for (JsonNode message : input.path("messages")) {
            JsonNode images = message.path("imageAttachments");
            if (images.isMissingNode()) continue;
            require(images.isArray());
            if (images.isEmpty()) continue;
            require("USER".equals(message.path("sender").asString()));
            for (JsonNode image : images) {
                UUID id = uuid(image.path("attachmentId"));
                String digest = text(image.path("digest"));
                require(digest.matches("[0-9a-f]{64}") && found.put(id,
                        new Source(uuid(message.path("id")), positiveInt(message.path("revision")), digest)) == null);
            }
        }
        return found;
    }

    private static boolean proposedProblem(JsonNode input, JsonNode result, String ref) {
        if (ref.startsWith("new:")) {
            if (!ref.matches("new:[A-Za-z0-9_-]+")) return false;
            for (JsonNode proposal : result.path("problemProposals")) {
                if (ref.equals(proposal.path("problemRef").asString())) return true;
            }
        } else {
            for (JsonNode problem : input.path("problems")) {
                if (ref.equals(problem.path("id").asString())) return true;
            }
        }
        return false;
    }

    private static boolean hasQuestion(JsonNode result, Answer answer, String problemRef, UUID problemId,
            String questionRef, String observation) {
        boolean stored = false;
        for (JsonNode bundle : answer.payload().path("questionBundles")) {
            if (problemId.toString().equals(bundle.path("problemId").asString())
                    && questionRef.equals(bundle.path("bundleRef").asString())) stored = true;
        }
        if (!stored) return false;
        for (JsonNode question : result.path("questions")) {
            if (problemRef.equals(question.path("problemRef").asString())
                    && questionRef.equals(question.path("bundleRef").asString())
                    && question.path("text").isString()) {
                String body = question.path("text").asString();
                return body.contains(observation) && answer.content().contains(body);
            }
        }
        return false;
    }

    private static boolean containsSensitiveText(JsonNode node) {
        if (node.isString()) return !SensitiveTextMasker.mask(node.asString()).equals(node.asString());
        if (node.isObject()) {
            for (String name : node.propertyNames()) {
                if (!SensitiveTextMasker.mask(name).equals(name)) return true;
            }
        }
        for (JsonNode child : node) if (containsSensitiveText(child)) return true;
        return false;
    }

    private static boolean validUnicode(String text) {
        return text.codePoints().noneMatch(value -> value >= 0xD800 && value <= 0xDFFF);
    }

    private static String text(JsonNode node) {
        require(node.isString() && node.asString().codePoints().anyMatch(value ->
                !Character.isWhitespace(value) && !Character.isSpaceChar(value) && value != 0xFEFF));
        return node.asString();
    }

    private static UUID uuid(JsonNode node) {
        String value = text(node);
        try {
            UUID result = UUID.fromString(value);
            require(result.toString().equals(value));
            return result;
        } catch (IllegalArgumentException exception) {
            throw invalid();
        }
    }

    private static int positiveInt(JsonNode node) {
        require(node.isIntegralNumber() && node.canConvertToInt() && node.asInt() > 0);
        return node.asInt();
    }

    private static void require(boolean condition) {
        if (!condition) throw invalid();
    }

    private static AgentResultRejectedException invalid() {
        return new AgentResultRejectedException("INVALID_OUTPUT", "이미지 관찰 결과를 검증할 수 없습니다");
    }

    private record Source(UUID messageId, int revision, String digest) { }
    private record Answer(String content, JsonNode payload) { }
}
