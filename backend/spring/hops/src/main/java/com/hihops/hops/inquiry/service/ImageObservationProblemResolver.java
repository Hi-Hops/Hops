package com.hihops.hops.inquiry.service;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.hihops.hops.agent.service.AgentResultRejectedException;

import lombok.RequiredArgsConstructor;
import tools.jackson.databind.JsonNode;

/** 이미지 관찰의 문제 참조를 같은 거래에서 확인된 문제 ID로 해석한다. */
@Component
@RequiredArgsConstructor
public class ImageObservationProblemResolver {
    private final NamedParameterJdbcTemplate jdbc;

    @Transactional(propagation = Propagation.MANDATORY)
    public Map<String, UUID> resolve(UUID runId, UUID inquiryId, JsonNode input, JsonNode result,
            GuideResultWriter.@Nullable Applied guide, ProblemQuestionWriter.Applied stored) {
        JsonNode observations = result.path("imageObservations");
        if (observations.isMissingNode()) return Map.of();
        require(observations.isArray() && observations.size() <= 15);
        Set<String> required = new HashSet<>();
        for (JsonNode observation : observations) {
            JsonNode ref = observation.path("problemRef");
            require(ref.isString() && !ref.asString().isBlank());
            required.add(ref.asString());
        }
        if (required.isEmpty()) return Map.of();

        Map<String, UUID> resolved = new HashMap<>();
        stored.problemIds().forEach((ref, id) -> add(required, resolved, ref, id));
        if (guide != null) add(required, resolved, guide.problemRef(), guide.problemId());
        require(input.path("problems").isArray());
        for (JsonNode problem : input.path("problems")) {
            String ref = problem.path("id").asString("");
            if (!required.contains(ref)) continue;
            try {
                UUID id = UUID.fromString(ref);
                require(id.toString().equals(ref));
                add(required, resolved, ref, id);
            } catch (IllegalArgumentException exception) {
                throw invalid();
            }
        }
        jdbc.query("""
                SELECT r.problem_ref, r.problem_id FROM agent_ticket_request r
                JOIN inquiry_problem p ON p.id = r.problem_id
                WHERE r.run_id = :run AND r.problem_ref IN (:refs)
                  AND p.inquiry_id = :inquiry AND p.deleted_at IS NULL
                """, Map.of("run", runId, "refs", required, "inquiry", inquiryId), rs -> {
            add(required, resolved, rs.getString("problem_ref"), rs.getObject("problem_id", UUID.class));
        });
        require(resolved.keySet().equals(required));
        var validIds = jdbc.queryForList("SELECT id FROM inquiry_problem WHERE inquiry_id = :inquiry "
                + "AND deleted_at IS NULL AND id IN (:ids)",
                Map.of("inquiry", inquiryId, "ids", new HashSet<>(resolved.values())), UUID.class);
        require(new HashSet<>(validIds).equals(new HashSet<>(resolved.values())));
        return Map.copyOf(resolved);
    }

    private static void add(Set<String> required, Map<String, UUID> result, String ref, UUID id) {
        if (!required.contains(ref)) return;
        require(id != null);
        UUID previous = result.putIfAbsent(ref, id);
        require(previous == null || previous.equals(id));
    }

    private static void require(boolean valid) {
        if (!valid) throw invalid();
    }

    private static AgentResultRejectedException invalid() {
        return new AgentResultRejectedException("INVALID_OUTPUT", "이미지 관찰의 문제 참조를 확인할 수 없습니다");
    }
}
