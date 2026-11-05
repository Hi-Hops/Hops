package com.hihops.hops.relation.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import com.hihops.hops.knowledge.domain.AccessLevel;
import com.hihops.hops.knowledge.domain.ReviewStatus;
import com.hihops.hops.relation.domain.RelationCause;
import com.hihops.hops.relation.domain.RelationCheckItem;
import com.hihops.hops.relation.domain.RelationQuestionTemplate;
import com.hihops.hops.relation.domain.RelationSymptom;

/**
 * 관계 데이터 seed(R__seed_dev_relations.sql)를 실제로 적재해 정본 6.1절(관계 카탈로그)과 같은지, 공개·근거 규칙이 지켜지는지,
 * 엔티티와 저장소가 테이블을 읽는지 확인한다. 이 파일은 코퍼스·질문 카드·사례 카드에서 자동 생성된다.
 * 원본과 SQL이 어긋났는지는 scripts/knowledge의 검사가 맡는다.
 */
@SpringBootTest
@ActiveProfiles({"db", "test"})
@Transactional
class RelationSeedTest {

    @Autowired private JdbcTemplate jdbc;
    @Autowired private RelationSymptomRepository symptoms;
    @Autowired private RelationCauseRepository causes;
    @Autowired private RelationCheckItemRepository checkItems;
    @Autowired private RelationQuestionTemplateRepository questions;
    @Autowired private RelationSymptomCauseRepository symptomCauses;
    @Autowired private RelationCauseCheckRepository causeChecks;
    @Autowired private RelationCaseSymptomRepository caseSymptoms;
    @Autowired private RelationCaseCheckRepository caseChecks;

    @Test
    void 노드와_연결_행_수가_정본_카탈로그대로다() throws Exception {
        loadSeeds();

        assertThat(count("relation_symptom")).isEqualTo(5);
        assertThat(count("relation_cause")).isEqualTo(15);
        assertThat(count("relation_check_item")).isEqualTo(21);
        assertThat(count("relation_question_template")).isEqualTo(20);
        assertThat(count("relation_symptom_cause")).isEqualTo(15);
        assertThat(count("relation_cause_check")).isEqualTo(25);
        assertThat(count("relation_case_symptom")).isEqualTo(6);
        assertThat(count("relation_case_check")).isEqualTo(11);
    }

    @Test
    void 모든_행이_승인_상태이고_공개_범위가_정본_규칙대로다() throws Exception {
        loadSeeds();

        for (String table : List.of("relation_symptom", "relation_cause", "relation_check_item", "relation_question_template",
                "relation_symptom_cause", "relation_cause_check", "relation_case_symptom", "relation_case_check")) {
            assertThat(jdbc.queryForObject("SELECT count(*) FROM " + table + " WHERE review_status <> 'APPROVED' OR deleted_at IS NOT NULL",
                    Integer.class)).as(table).isZero();
        }
        // 증상은 직원이 말하는 현상, 나머지는 담당자 전용, 질문 문구는 직원 공개
        assertThat(distinct("relation_symptom")).containsExactly("EMPLOYEE");
        assertThat(distinct("relation_cause")).containsExactly("STAFF");
        assertThat(distinct("relation_check_item")).containsExactly("STAFF");
        assertThat(distinct("relation_question_template")).containsExactly("EMPLOYEE");
        assertThat(distinct("relation_symptom_cause")).containsExactly("STAFF");
        assertThat(distinct("relation_cause_check")).containsExactly("STAFF");
        assertThat(distinct("relation_case_symptom")).containsExactly("STAFF");
        assertThat(distinct("relation_case_check")).containsExactly("STAFF");
    }

    @Test
    void 질문_문구는_직원_공개_확인_항목에만_있다() throws Exception {
        loadSeeds();

        // 직원 공개 확인 항목 20개마다 질문이 있고, 담당자 전용 확인 항목(1개)에는 질문이 없다.
        assertThat(count("relation_check_item WHERE employee_disclosable")).isEqualTo(20);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM relation_check_item c WHERE c.employee_disclosable "
                + "AND NOT EXISTS (SELECT 1 FROM relation_question_template t WHERE t.check_item_id = c.id)", Integer.class)).isZero();
        assertThat(jdbc.queryForList("SELECT catalog_key FROM relation_check_item c WHERE NOT c.employee_disclosable", String.class))
                .containsExactly("check-ea-view-permission-record");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM relation_question_template t JOIN relation_check_item c ON c.id = t.check_item_id "
                + "WHERE t.employee_disclosable AND NOT c.employee_disclosable", Integer.class)).isZero();
        // 직원에게 나가는 질문에 원인 이름이나 내부 ID가 없다.
        assertThat(jdbc.queryForObject("SELECT count(*) FROM relation_question_template WHERE question_text ~ '(sym-|cause-|check-|원인|런북)'",
                Integer.class)).isZero();
    }

    @Test
    void 증상_원인_확인_항목의_시스템과_근거_문서가_맞다() throws Exception {
        loadSeeds();

        assertThat(jdbc.queryForObject("SELECT count(*) FROM relation_symptom s JOIN system_feature f ON f.id = s.system_feature_id "
                + "WHERE f.it_system_id <> s.it_system_id", Integer.class)).isZero();
        // 원인과 확인 항목은 연결된 증상과 같은 시스템이다.
        assertThat(jdbc.queryForObject("SELECT count(*) FROM relation_symptom_cause e JOIN relation_symptom s ON s.id = e.symptom_id "
                + "JOIN relation_cause c ON c.id = e.cause_id WHERE s.it_system_id <> c.it_system_id", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM relation_cause_check e JOIN relation_cause c ON c.id = e.cause_id "
                + "JOIN relation_check_item k ON k.id = e.check_item_id WHERE c.it_system_id <> k.it_system_id", Integer.class)).isZero();
        // 근거는 항목을 선언한 문서의 현재 버전이다: 런북 4건과 권한 신청 정책.
        assertThat(sourceDocumentKeys("relation_symptom")).containsExactlyInAnyOrder("runbook-ea-document-not-listed",
                "runbook-ea-attachment-open-failure", "runbook-login-sso-loop", "runbook-file-open-denied", "policy-login-permission-approval");
        // 근거 문서는 모두 승인된 현재 버전(1)이다.
        assertThat(jdbc.queryForObject("SELECT count(*) FROM relation_symptom s JOIN knowledge_document_version v ON v.id = s.source_document_version_id "
                + "WHERE v.version_no <> 1 OR v.review_status <> 'APPROVED'", Integer.class)).isZero();
    }

    @Test
    void 증상에서_원인_확인_항목_질문으로_저장소를_따라_탐색한다() throws Exception {
        loadSeeds();

        RelationSymptom symptom = symptoms.findByCatalogKeyAndDeletedAtIsNull("sym-ea-doc-not-listed").orElseThrow();
        assertThat(symptom.getSystemFeature().getCode()).isEqualTo("DOCUMENT_VIEW");
        assertThat(symptom.getAccessLevel()).isEqualTo(AccessLevel.EMPLOYEE);
        assertThat(symptoms.findByItSystemIdAndDeletedAtIsNullOrderByCatalogKey(symptom.getItSystem().getId()))
                .extracting(RelationSymptom::getCatalogKey).containsExactly("sym-ea-attach-not-open", "sym-ea-doc-not-listed");

        List<String> causeKeys = symptomCauses.findBySymptomIdAndDeletedAtIsNull(symptom.getId()).stream()
                .map(edge -> edge.getCause().getCatalogKey()).sorted().toList();
        assertThat(causeKeys).containsExactly("cause-ea-draft-not-submitted", "cause-ea-filter-narrow",
                "cause-ea-no-view-permission", "cause-ea-period-out-of-range");

        RelationCause cause = causes.findByCatalogKeyAndDeletedAtIsNull("cause-ea-no-view-permission").orElseThrow();
        List<RelationCheckItem> checks = causeChecks.findByCauseIdAndDeletedAtIsNull(cause.getId()).stream()
                .map(edge -> edge.getCheckItem()).sorted((a, b) -> a.getCatalogKey().compareTo(b.getCatalogKey())).toList();
        assertThat(checks).extracting(RelationCheckItem::getCatalogKey)
                .containsExactly("check-ea-participation", "check-ea-view-permission-record");

        // 직원에게 물을 수 있는 질문은 직원 공개 확인 항목의 승인된 문구뿐이다.
        RelationCheckItem participation = checks.get(0);
        assertThat(participation.isEmployeeDisclosable()).isTrue();
        List<RelationQuestionTemplate> askable = questions.findAskableByCheckItemId(participation.getId());
        assertThat(askable).extracting(RelationQuestionTemplate::getCatalogKey).containsExactly("q-ea-participation-01");
        assertThat(askable.get(0).getQuestionText()).contains("기안");
        assertThat(askable.get(0).getReviewStatus()).isEqualTo(ReviewStatus.APPROVED);
        RelationCheckItem staffOnly = checks.get(1);
        assertThat(staffOnly.isEmployeeDisclosable()).isFalse();
        assertThat(questions.findAskableByCheckItemId(staffOnly.getId())).isEmpty();
    }

    @Test
    void 승인_사례는_증상과_확인_항목에_연결되고_근거는_사례_문서_버전이다() throws Exception {
        loadSeeds();

        RelationSymptom fileSymptom = symptoms.findByCatalogKeyAndDeletedAtIsNull("sym-file-not-open").orElseThrow();
        List<String> caseNumbers = caseSymptoms.findBySymptomIdAndDeletedAtIsNull(fileSymptom.getId()).stream()
                .map(edge -> edge.getResolvedCase().getCaseNumber()).sorted().toList();
        // 같은 증상을 다룬 사례 2건: 폴더 접근 권한 없음, 링크 만료
        assertThat(caseNumbers).containsExactly("SEED-C-005", "SEED-C-006");

        UUID linkExpiredCase = jdbc.queryForObject("SELECT id FROM resolved_case WHERE case_number = 'SEED-C-006'", UUID.class);
        assertThat(caseChecks.findByResolvedCaseIdAndDeletedAtIsNull(linkExpiredCase)).extracting(edge -> edge.getCheckItem().getCatalogKey())
                .containsExactlyInAnyOrder("check-file-error-message", "check-file-link-received");
        assertThat(caseSymptoms.findByResolvedCaseIdAndDeletedAtIsNull(linkExpiredCase)).hasSize(1);
        // 연결의 근거는 사례의 CASE 지식 문서 버전이고, 그 문서의 ID는 사례 ID와 같다.
        assertThat(jdbc.queryForObject("SELECT count(*) FROM relation_case_symptom e JOIN knowledge_document_version v ON v.id = e.source_document_version_id "
                + "WHERE v.knowledge_document_id = e.resolved_case_id", Integer.class)).isEqualTo(6);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM relation_case_check e JOIN knowledge_document_version v ON v.id = e.source_document_version_id "
                + "WHERE v.knowledge_document_id = e.resolved_case_id", Integer.class)).isEqualTo(11);
    }

    @Test
    void 엔티티로_저장한_행도_제약을_따른다() throws Exception {
        loadSeeds();

        RelationSymptom base = symptoms.findByCatalogKeyAndDeletedAtIsNull("sym-login-loop").orElseThrow();
        var source = base.getSourceDocumentVersion();
        RelationCheckItem item = checkItems.saveAndFlush(
                new RelationCheckItem("check-login-extra", "추가 확인 항목", base.getItSystem(), true, AccessLevel.STAFF, source));
        assertThat(item.getReviewStatus()).isEqualTo(ReviewStatus.PENDING);
        // 승인 전에는 직원에게 물을 수 있는 문구에 나오지 않는다.
        RelationQuestionTemplate template = questions.saveAndFlush(
                new RelationQuestionTemplate("q-login-extra-01", item, "추가로 확인할 점이 있나요?", true, AccessLevel.EMPLOYEE, source));
        assertThat(questions.findAskableByCheckItemId(item.getId())).isEmpty();
        item.approve();
        template.approve();
        checkItems.saveAndFlush(item);
        questions.saveAndFlush(template);
        assertThat(questions.findAskableByCheckItemId(item.getId())).extracting(RelationQuestionTemplate::getCatalogKey)
                .containsExactly("q-login-extra-01");
    }

    @Test
    void DB_제약이_근거_없는_항목과_잘못된_공개_표시를_막는다() throws Exception {
        loadSeeds();

        UUID system = jdbc.queryForObject("SELECT id FROM it_system WHERE code = 'COMMON_LOGIN'", UUID.class);
        UUID source = jdbc.queryForObject("SELECT source_document_version_id FROM relation_cause LIMIT 1", UUID.class);
        UUID checkItem = jdbc.queryForObject("SELECT id FROM relation_check_item LIMIT 1", UUID.class);
        String insertCause = "INSERT INTO relation_cause (catalog_key, label, it_system_id, access_level, source_document_version_id) "
                + "VALUES (?, ?, ?, 'STAFF', ?)";
        // 근거 문서 버전이 없는 원인
        rejected(insertCause, "cause-x", "원인", system, UUID.randomUUID());
        // 직원에게 나갈 수 있다고 표시했지만 담당자 전용 범위인 질문 문구
        rejected("INSERT INTO relation_question_template (catalog_key, check_item_id, question_text, employee_disclosable, access_level, source_document_version_id) "
                + "VALUES ('q-x-01', ?, '질문', true, 'STAFF', ?)", checkItem, source);
        // 비어 있는 이름, 중복 카탈로그 ID
        rejected(insertCause, "cause-y", "  ", system, source);
        rejected(insertCause, "cause-login-cookie-blocked", "중복", system, source);
        // 같은 증상-원인 연결을 두 번 만들 수 없다
        rejected("INSERT INTO relation_symptom_cause (symptom_id, cause_id, access_level, source_document_version_id) "
                + "SELECT symptom_id, cause_id, access_level, source_document_version_id FROM relation_symptom_cause LIMIT 1");
    }

    /** 제약 위반을 확인한다. 위반하면 거래가 중단되므로 savepoint로 되돌려 다음 확인을 이어 간다. */
    private void rejected(String sql, Object... args) {
        jdbc.execute("SAVEPOINT expected_violation");
        assertThatThrownBy(() -> jdbc.update(sql, args)).isInstanceOf(DataIntegrityViolationException.class);
        jdbc.execute("ROLLBACK TO SAVEPOINT expected_violation");
    }

    @Test
    void 두_번_적재해도_행이_늘지_않는다() throws Exception {
        loadSeeds();
        loadSeeds();

        assertThat(count("relation_symptom")).isEqualTo(5);
        assertThat(count("relation_cause")).isEqualTo(15);
        assertThat(count("relation_check_item")).isEqualTo(21);
        assertThat(count("relation_question_template")).isEqualTo(20);
        assertThat(count("relation_symptom_cause")).isEqualTo(15);
        assertThat(count("relation_cause_check")).isEqualTo(25);
        assertThat(count("relation_case_symptom")).isEqualTo(6);
        assertThat(count("relation_case_check")).isEqualTo(11);
    }

    private int count(String fromClause) {
        return jdbc.queryForObject("SELECT count(*) FROM " + fromClause, Integer.class);
    }

    private List<String> distinct(String table) {
        return jdbc.queryForList("SELECT DISTINCT access_level FROM " + table, String.class);
    }

    private List<String> sourceDocumentKeys(String table) {
        return jdbc.queryForList("SELECT DISTINCT d.source_data ->> 'documentKey' FROM " + table + " t "
                + "JOIN knowledge_document_version v ON v.id = t.source_document_version_id "
                + "JOIN knowledge_document d ON d.id = v.knowledge_document_id", String.class);
    }

    private void loadSeeds() throws Exception {
        // R__ 파일은 이름 순으로 실행된다. 본문이 $kb$ 인용 안에 세미콜론을 포함하므로 문장을 나누지 않고 서버에 통째로 보낸다.
        for (String file : List.of("R__seed_dev_department_member.sql", "R__seed_dev_it_system.sql", "R__seed_dev_knowledge.sql",
                "R__seed_dev_knowledge_cases.sql", "R__seed_dev_relations.sql")) {
            jdbc.execute(new ClassPathResource("db/seed/" + file).getContentAsString(StandardCharsets.UTF_8).replace("\r\n", "\n"));
        }
    }
}
