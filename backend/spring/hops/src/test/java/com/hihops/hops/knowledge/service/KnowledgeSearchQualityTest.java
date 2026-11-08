package com.hihops.hops.knowledge.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import com.hihops.hops.knowledge.domain.AccessLevel;
import com.hihops.hops.knowledge.domain.DocumentType;

/**
 * 한국어 질의 10개로 1차 SQL 검색이 상위 5건 안에 정답 문서를 찾는지 확인한다(contracts/agents/KNOWLEDGE_TOOLS.md "한국어 검색 품질 확인").
 * 질의는 평가 DEV 시나리오의 문구와 직접 쓴 변형이며, 동결한 TEST 시나리오의 문구는 쓰지 않는다.
 * 정답은 문서 카탈로그(VIRTUAL_COMPANY.md 6절)에서 사람이 정했다. 직원 상담 범위(가이드·정책)에서 AI가 하듯 보고 시스템으로 좁혀 찾는다.
 * PostgreSQL FTS(simple, 접두 일치)와 비교한 값도 함께 센다. 모델 품질이 아니라 검색 규칙의 간이 확인이다.
 */
@SpringBootTest
@ActiveProfiles({"db", "test"})
@Transactional
class KnowledgeSearchQualityTest {
    private static final String REQUESTER = "a0000000-0000-7000-8000-000000000101";

    record Case(String query, String systemCode, String expected) {
    }

    static final List<Case> CASES = List.of(
            new Case("전자결재에서 지난 문서를 찾는 방법을 알려주세요.", "ELECTRONIC_APPROVAL", "guide-ea-document-search"),
            new Case("전자결재에서 임시 저장한 문서는 어디서 확인하나요?", "ELECTRONIC_APPROVAL", "guide-ea-document-create"),
            new Case("상신한 문서가 지금 어느 결재자 단계에서 멈췄는지 보고 싶어요.", "ELECTRONIC_APPROVAL", "guide-ea-approval-status"),
            new Case("첨부 파일을 눌러도 열리지 않는데 어떻게 열고 내려받나요?", "ELECTRONIC_APPROVAL", "guide-ea-attachment-open"),
            new Case("첨부할 수 있는 파일 크기와 개수가 얼마까지인가요?", "ELECTRONIC_APPROVAL", "policy-ea-attachment-limit"),
            new Case("공통 로그인 포털에서 다른 시스템 이용 권한을 신청하고 싶어요.", "COMMON_LOGIN", "guide-login-permission-request"),
            new Case("비밀번호를 여러 번 틀렸더니 계정이 잠겼어요.", "COMMON_LOGIN", "policy-login-account-lock"),
            new Case("사내 포털에 한 번 로그인하면 다른 시스템에서도 다시 로그인하지 않아도 되나요?", "COMMON_LOGIN", "guide-login-sso-use"),
            new Case("파일 공유에서 동료에게 파일 링크를 만들어 보내고 싶어요.", "FILE_SHARING", "guide-file-share-link"),
            new Case("공유 폴더에 접근 권한이 없다고 나오는데 누구에게 열려 있는 건가요?", "FILE_SHARING", "policy-file-share-scope"));

    @Autowired JdbcTemplate jdbc;
    @Autowired KnowledgeSearchService search;

    @Test
    void 시나리오_질의_10개의_상위_5건에_정답_문서가_든다() throws Exception {
        for (String file : List.of("R__seed_dev_department_member.sql", "R__seed_dev_it_system.sql", "R__seed_dev_knowledge.sql")) {
            jdbc.execute(new ClassPathResource("db/seed/" + file).getContentAsString(StandardCharsets.UTF_8).replace("\r\n", "\n"));
        }
        UUID requester = UUID.fromString(REQUESTER);
        StringBuilder report = new StringBuilder("| # | 질의 | 정답 | 현재 규칙 순위 | FTS(simple, 접두) 순위 | 시스템 미지정 순위 |\n|---|---|---|---|---|---|\n");
        int ours = 0;
        int fts = 0;
        int unnarrowed = 0;
        int top1 = 0;
        int ftsTop1 = 0;
        List<String> misses = new ArrayList<>();
        for (int i = 0; i < CASES.size(); i++) {
            Case c = CASES.get(i);
            UUID system = jdbc.queryForObject("SELECT id FROM it_system WHERE code = ?", UUID.class, c.systemCode());
            var hits = search.search(new KnowledgeSearchService.Query(KnowledgeQueryTokens.of(c.query()), Set.of(AccessLevel.EMPLOYEE),
                    Set.of(DocumentType.EMPLOYEE_GUIDE, DocumentType.POLICY), Set.of(system), requester, 5));
            List<String> keys = hits.stream().map(hit -> hit.assembled().documentKey()).toList();
            int rank = keys.indexOf(c.expected()) + 1;
            int ftsRank = ftsRank(c, system);
            Set<UUID> allSystems = Set.copyOf(jdbc.queryForList("SELECT id FROM it_system WHERE deleted_at IS NULL", UUID.class));
            int wideRank = search.search(new KnowledgeSearchService.Query(KnowledgeQueryTokens.of(c.query()), Set.of(AccessLevel.EMPLOYEE),
                    Set.of(DocumentType.EMPLOYEE_GUIDE, DocumentType.POLICY), allSystems, requester, 5)).stream()
                    .map(hit -> hit.assembled().documentKey()).toList().indexOf(c.expected()) + 1;
            if (wideRank > 0) {
                unnarrowed++;
            }
            if (rank == 1) {
                top1++;
            }
            if (ftsRank == 1) {
                ftsTop1++;
            }
            if (rank > 0) {
                ours++;
            } else {
                misses.add(c.query());
            }
            if (ftsRank > 0) {
                fts++;
            }
            report.append("| %d | %s | %s | %s | %s | %s |%n".formatted(i + 1, c.query(), c.expected(),
                    rank > 0 ? String.valueOf(rank) : "없음", ftsRank > 0 ? String.valueOf(ftsRank) : "없음",
                    wideRank > 0 ? String.valueOf(wideRank) : "없음"));
        }
        report.append("%n상위 5건 안: 현재 규칙 %d/10, FTS(simple, 접두, 조사 제거 없음) %d/10, 시스템 미지정(12건 후보) %d/10%n"
                .formatted(ours, fts, unnarrowed));
        report.append("1위: 현재 규칙 %d/10, FTS %d/10%n".formatted(top1, ftsTop1));
        String out = System.getenv("HOPS_SEARCH_QUALITY_REPORT");
        if (out != null) {
            Files.writeString(Path.of(out), report.toString(), StandardCharsets.UTF_8);
        }
        assertThat(misses).as("상위 5건에 정답이 없는 질의").isEmpty();
        assertThat(unnarrowed).as("시스템을 좁히지 않아도 상위 5건에 정답이 든 질의 수").isGreaterThanOrEqualTo(9);
    }

    /** 비교용: 질의를 공백으로 나눈 단어(조사 제거 없음)를 접두 일치 OR로 찾는 simple FTS의 상위 5건 순위. */
    private int ftsRank(Case c, UUID system) {
        List<String> words = new ArrayList<>();
        for (String word : c.query().toLowerCase().split("[^\\p{L}\\p{N}]+")) {
            if (word.codePointCount(0, word.length()) >= 2) {
                words.add(word + ":*");
            }
        }
        String tsquery = String.join(" | ", words);
        List<String> keys = jdbc.queryForList("""
                SELECT d.source_data ->> 'documentKey' FROM knowledge_document d
                JOIN knowledge_document_version v ON v.knowledge_document_id = d.id
                WHERE d.access_level = 'EMPLOYEE' AND (d.it_system_id = ? OR d.it_system_id IS NULL)
                  AND to_tsvector('simple', d.title || ' ' || v.content) @@ to_tsquery('simple', ?)
                ORDER BY ts_rank(to_tsvector('simple', d.title || ' ' || v.content), to_tsquery('simple', ?)) DESC, d.id
                LIMIT 5
                """, String.class, system, tsquery, tsquery);
        return keys.indexOf(c.expected()) + 1;
    }
}
