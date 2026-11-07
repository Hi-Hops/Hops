package com.hihops.hops.relation.repository;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import lombok.RequiredArgsConstructor;

/**
 * 관계 조회(증상 → 원인 → 확인 항목 → 질문 문구, 사례 이웃)의 SQL. 모든 조회가 승인(APPROVED)·삭제 제외·활성 시스템 조건을 건다.
 *
 * <p>공개 범위는 항목 종류마다 다르다. 증상은 EMPLOYEE, 원인·확인 항목은 STAFF이지만 확인 항목 대부분이 직원 공개
 * (employee_disclosable)다. 그래서 access_level만으로 거르지 않고, 직원 전용 조회(staff=false)는 직원 공개 표시가 있는 확인 항목과
 * 그 승인된 EMPLOYEE 질문 문구만 돌려준다. 원인은 직원 전용 조회에서 확인 항목을 찾는 연결로만 쓰고 결과로 내보내지 않는다.
 * 담당자 범위(staff=true)는 해당 시스템의 주담당자(memberId)에게만 STAFF 행을 돌려준다.
 */
@Repository
@RequiredArgsConstructor
public class RelationSearchRepository {
    private final NamedParameterJdbcTemplate jdbc;

    /**
     * 읽는 주체. internalRead=true이면 내부 판단 읽기(옵션 프로필)라서 담당자 전용 항목의 주담당자 조건을 적용하지 않는다.
     * 사용자에게 닿는 결과에는 쓰지 않는다.
     */
    public record Viewer(UUID memberId, boolean internalRead) {
    }

    public record SymptomRow(UUID id, String catalogKey, String label, UUID systemId, UUID featureId, String featureCode,
            String accessLevel, UUID sourceVersionId) {
    }

    /** causeCount: 이 확인 항목이 가르는 후보 원인의 수. 정렬에만 쓰고 직원 응답에는 싣지 않는다. */
    public record CheckRow(UUID symptomId, UUID id, String catalogKey, String label, boolean employeeDisclosable,
            String accessLevel, UUID sourceVersionId, int causeCount) {
    }

    public record QuestionRow(UUID checkItemId, UUID id, String catalogKey, String text, String accessLevel, UUID sourceVersionId) {
    }

    public record CauseRow(UUID symptomId, UUID id, String catalogKey, String label, String accessLevel, UUID sourceVersionId) {
    }

    public record CauseCheckRow(UUID causeId, UUID checkItemId) {
    }

    public record CaseLinkRow(UUID symptomId, UUID caseId, int caseVersion) {
    }

    /** 기능이 속한 시스템. 없거나 삭제된 기능은 결과에 없다. */
    public Map<UUID, UUID> featureSystems(Collection<UUID> featureIds) {
        if (featureIds.isEmpty()) {
            return Map.of();
        }
        return jdbc.query("SELECT id, it_system_id FROM system_feature WHERE deleted_at IS NULL AND id IN (:ids)",
                new MapSqlParameterSource("ids", featureIds), (rs, row) -> Map.entry(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class)))
                .stream().collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    }

    public List<SymptomRow> findSymptoms(Collection<String> levels, Collection<UUID> systemIds, Viewer viewer) {
        if (levels.isEmpty() || systemIds.isEmpty()) {
            return List.of();
        }
        return jdbc.query("""
                SELECT s.id, s.catalog_key, s.label, s.it_system_id, s.system_feature_id, f.code, s.access_level, s.source_document_version_id
                FROM relation_symptom s
                JOIN system_feature f ON f.id = s.system_feature_id AND f.deleted_at IS NULL
                JOIN it_system sys ON sys.id = s.it_system_id AND sys.deleted_at IS NULL
                WHERE s.deleted_at IS NULL AND s.review_status = 'APPROVED'
                  AND s.access_level IN (:levels) AND (s.access_level = 'EMPLOYEE' OR (:internalRead OR sys.primary_owner_member_id = :memberId))
                  AND sys.id IN (:systemIds)
                ORDER BY s.catalog_key
                """, new MapSqlParameterSource("levels", levels).addValue("memberId", viewer.memberId()).addValue("internalRead", viewer.internalRead()).addValue("systemIds", systemIds),
                (rs, row) -> new SymptomRow(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3), rs.getObject(4, UUID.class),
                        rs.getObject(5, UUID.class), rs.getString(6), rs.getString(7), rs.getObject(8, UUID.class)));
    }

    /**
     * 증상의 후보 원인을 가르는 확인 항목. 원인과 연결은 승인된 것만 쓰며 공개 범위는 따지지 않는다(직원 전용 조회에서도 내부 계산에만 쓰고
     * 노출하지 않는다). 확인 항목은 직원 전용 조회이면 직원 공개 표시가 있어야 하고, 담당자 범위이면 직원 공개이거나 공개 범위·담당 조건을 만족해야 한다.
     */
    public List<CheckRow> findCheckItems(Collection<UUID> symptomIds, boolean staff, Collection<String> levels, Viewer viewer) {
        if (symptomIds.isEmpty()) {
            return List.of();
        }
        String visible = staff
                ? "(k.employee_disclosable OR (k.access_level IN (:levels) AND (k.access_level = 'EMPLOYEE' OR (:internalRead OR sys.primary_owner_member_id = :memberId))))"
                : "k.employee_disclosable";
        return jdbc.query("""
                SELECT sc.symptom_id, k.id, k.catalog_key, k.label, k.employee_disclosable, k.access_level, k.source_document_version_id,
                       count(DISTINCT c.id)
                FROM relation_symptom_cause sc
                JOIN relation_cause c ON c.id = sc.cause_id AND c.deleted_at IS NULL AND c.review_status = 'APPROVED'
                JOIN relation_cause_check cc ON cc.cause_id = c.id AND cc.deleted_at IS NULL AND cc.review_status = 'APPROVED'
                JOIN relation_check_item k ON k.id = cc.check_item_id AND k.deleted_at IS NULL AND k.review_status = 'APPROVED'
                JOIN it_system sys ON sys.id = k.it_system_id AND sys.deleted_at IS NULL
                WHERE sc.deleted_at IS NULL AND sc.review_status = 'APPROVED' AND sc.symptom_id IN (:symptomIds) AND %s
                GROUP BY sc.symptom_id, k.id, k.catalog_key, k.label, k.employee_disclosable, k.access_level, k.source_document_version_id
                ORDER BY sc.symptom_id, count(DISTINCT c.id) DESC, k.catalog_key
                """.formatted(visible), new MapSqlParameterSource("symptomIds", symptomIds).addValue("levels", levels).addValue("memberId", viewer.memberId()).addValue("internalRead", viewer.internalRead()),
                (rs, row) -> new CheckRow(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getString(3), rs.getString(4),
                        rs.getBoolean(5), rs.getString(6), rs.getObject(7, UUID.class), rs.getInt(8)));
    }

    /** 확인 항목의 질문 문구. 직원 전용 조회는 직원 공개 표시와 EMPLOYEE 범위가 있는 승인된 문구만 돌려준다. */
    public List<QuestionRow> findQuestions(Collection<UUID> checkItemIds, boolean staff, Collection<String> levels, Viewer viewer) {
        if (checkItemIds.isEmpty()) {
            return List.of();
        }
        String visible = staff
                ? "t.access_level IN (:levels) AND (t.access_level = 'EMPLOYEE' OR (:internalRead OR sys.primary_owner_member_id = :memberId))"
                : "t.employee_disclosable AND k.employee_disclosable AND t.access_level = 'EMPLOYEE'";
        return jdbc.query("""
                SELECT t.check_item_id, t.id, t.catalog_key, t.question_text, t.access_level, t.source_document_version_id
                FROM relation_question_template t
                JOIN relation_check_item k ON k.id = t.check_item_id AND k.deleted_at IS NULL AND k.review_status = 'APPROVED'
                JOIN it_system sys ON sys.id = k.it_system_id AND sys.deleted_at IS NULL
                WHERE t.deleted_at IS NULL AND t.review_status = 'APPROVED' AND t.check_item_id IN (:checkItemIds) AND %s
                ORDER BY t.check_item_id, t.catalog_key
                """.formatted(visible), new MapSqlParameterSource("checkItemIds", checkItemIds).addValue("levels", levels).addValue("memberId", viewer.memberId()).addValue("internalRead", viewer.internalRead()),
                (rs, row) -> new QuestionRow(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getString(3), rs.getString(4),
                        rs.getString(5), rs.getObject(6, UUID.class)));
    }

    /** 담당자 범위에서만 쓴다. 증상의 가능 원인 중 공개 범위·담당 조건을 만족하는 승인된 것. */
    public List<CauseRow> findCauses(Collection<UUID> symptomIds, Collection<String> levels, Viewer viewer) {
        if (symptomIds.isEmpty() || levels.isEmpty()) {
            return List.of();
        }
        return jdbc.query("""
                SELECT sc.symptom_id, c.id, c.catalog_key, c.label, c.access_level, c.source_document_version_id
                FROM relation_symptom_cause sc
                JOIN relation_cause c ON c.id = sc.cause_id AND c.deleted_at IS NULL AND c.review_status = 'APPROVED'
                JOIN it_system sys ON sys.id = c.it_system_id AND sys.deleted_at IS NULL
                WHERE sc.deleted_at IS NULL AND sc.review_status = 'APPROVED' AND sc.symptom_id IN (:symptomIds)
                  AND sc.access_level IN (:levels) AND (sc.access_level = 'EMPLOYEE' OR (:internalRead OR sys.primary_owner_member_id = :memberId))
                  AND c.access_level IN (:levels) AND (c.access_level = 'EMPLOYEE' OR (:internalRead OR sys.primary_owner_member_id = :memberId))
                ORDER BY sc.symptom_id, c.catalog_key
                """, new MapSqlParameterSource("symptomIds", symptomIds).addValue("levels", levels).addValue("memberId", viewer.memberId()).addValue("internalRead", viewer.internalRead()),
                (rs, row) -> new CauseRow(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getString(3), rs.getString(4),
                        rs.getString(5), rs.getObject(6, UUID.class)));
    }

    public List<CauseCheckRow> findCauseChecks(Collection<UUID> causeIds) {
        if (causeIds.isEmpty()) {
            return List.of();
        }
        return jdbc.query("""
                SELECT cc.cause_id, cc.check_item_id FROM relation_cause_check cc
                WHERE cc.deleted_at IS NULL AND cc.review_status = 'APPROVED' AND cc.cause_id IN (:causeIds)
                """, new MapSqlParameterSource("causeIds", causeIds),
                (rs, row) -> new CauseCheckRow(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class)));
    }

    public record SystemCheckRow(UUID id, String catalogKey, String label) {
    }

    /**
     * 시스템의 승인된 확인 항목 전체(증상과 무관). 사례를 연결할 후보를 정하는 데 쓴다. 직원 공개 표시가 있거나 공개 범위·담당 조건을
     * 만족하는 항목만 돌려주며 catalogKey 순이다.
     */
    public List<SystemCheckRow> findSystemCheckItems(UUID systemId, Collection<String> levels, Viewer viewer) {
        if (levels.isEmpty()) {
            return List.of();
        }
        return jdbc.query("""
                SELECT k.id, k.catalog_key, k.label
                FROM relation_check_item k
                JOIN it_system sys ON sys.id = k.it_system_id AND sys.deleted_at IS NULL
                WHERE k.deleted_at IS NULL AND k.review_status = 'APPROVED' AND k.it_system_id = :systemId
                  AND (k.employee_disclosable OR (k.access_level IN (:levels)
                       AND (k.access_level = 'EMPLOYEE' OR (:internalRead OR sys.primary_owner_member_id = :memberId))))
                ORDER BY k.catalog_key
                """, new MapSqlParameterSource("systemId", systemId).addValue("levels", levels)
                .addValue("memberId", viewer.memberId()).addValue("internalRead", viewer.internalRead()),
                (rs, row) -> new SystemCheckRow(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3)));
    }

    /** 증상을 다룬 승인 사례(ADDRESSES). 연결의 공개 범위·담당 조건만 보며 사례 문서의 현재 공개 판정은 호출 쪽이 다시 한다. */
    public List<CaseLinkRow> findCaseLinks(Collection<UUID> symptomIds, Collection<String> levels, Viewer viewer) {
        if (symptomIds.isEmpty() || levels.isEmpty()) {
            return List.of();
        }
        return jdbc.query("""
                SELECT cs.symptom_id, c.id, c.version_no
                FROM relation_case_symptom cs
                JOIN resolved_case c ON c.id = cs.resolved_case_id AND c.deleted_at IS NULL AND c.case_status = 'APPROVED'
                JOIN it_system sys ON sys.id = c.it_system_id AND sys.deleted_at IS NULL
                WHERE cs.deleted_at IS NULL AND cs.review_status = 'APPROVED' AND cs.symptom_id IN (:symptomIds)
                  AND cs.access_level IN (:levels) AND (cs.access_level = 'EMPLOYEE' OR (:internalRead OR sys.primary_owner_member_id = :memberId))
                ORDER BY cs.symptom_id, c.case_number
                """, new MapSqlParameterSource("symptomIds", symptomIds).addValue("levels", levels).addValue("memberId", viewer.memberId()).addValue("internalRead", viewer.internalRead()),
                (rs, row) -> new CaseLinkRow(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getInt(3)));
    }
}
