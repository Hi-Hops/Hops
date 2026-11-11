package com.hihops.hops.ticket.service;

import static org.assertj.core.api.Assertions.assertThat;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import com.hihops.hops.ticket.dto.*;
import com.hihops.hops.ticket.dto.StandardRequestResponse.*;

class TicketMaterialAssessmentTest {
    private final TicketMaterialAssessment service = new TicketMaterialAssessment();
    private final JsonMapper mapper = JsonMapper.builder().build();

    @Test void 명시된_기능_화면을_대조하고_문의자_원문_인용을_보존한다() {
        String applicability = "기능: 문서 조회";
        var item = RecommendedCaseResponse.of(new KnowledgeEvidenceResponse(UUID.randomUUID(), UUID.randomUUID(),
                2, "CASE", "조회 사례", applicability, UUID.randomUUID(), null, applicability, "SYSTEM_STAFF", "SYNTHETIC"), null);
        var request = new StandardRequestResponse(UUID.randomUUID(), UUID.randomUUID(), 1, "조회 누락",
                List.of(), List.of(), List.of(), item.systemId(), List.of(), null, List.of());
        String content = "전자결재 문서 조회 화면의 문서함 목록입니다.";
        var source = new RelatedMessageResponse(UUID.randomUUID(), 1, "USER", content, List.of(), Instant.now());
        var result = service.assess(item, request, List.of(source), "문서 조회");
        assertThat(result.conditions()).singleElement().satisfies(c -> assertThat(c.status().name()).isEqualTo("MATCHED"));
        assertThat(result.quotePairs()).singleElement().satisfies(p -> {
            assertThat(p.currentQuote()).isEqualTo(content);
            assertThat(content.substring(p.currentStart(), p.currentEnd())).isEqualTo(content);
        });
    }

    @Test void 원문에서_확인한_조회기간을_비교하고_정확한_인용_위치를_보존한다() {
        String applicability = "조회 기간: 기안일 포함";
        var item = RecommendedCaseResponse.of(new KnowledgeEvidenceResponse(UUID.randomUUID(), UUID.randomUUID(),
                2, "CASE", "조회 조건 확인", "조치\n\n" + applicability, UUID.randomUUID(), null,
                applicability, "SYSTEM_STAFF", "SYNTHETIC"), null);
        var request = new StandardRequestResponse(UUID.randomUUID(), UUID.randomUUID(), 1, "조회 누락",
                List.of(), List.of(), List.of(), item.systemId(), List.of(), null, List.of());
        String content = "조회 상태는 승인이고, 조회 기간에는 오늘 기안일이 포함됩니다. 제목 검색어는 비워 두었어요.";
        var source = new RelatedMessageResponse(UUID.randomUUID(), 1, "USER", content, List.of(), Instant.now());
        var result = service.assess(item, request, List.of(source));
        assertThat(result.conditions()).singleElement().satisfies(c -> {
            assertThat(c.label()).isEqualTo("조회 기간");
            assertThat(c.status().name()).isEqualTo("MATCHED");
        });
        assertThat(result.quotePairs()).singleElement().satisfies(pair -> {
            assertThat(content.substring(pair.currentStart(), pair.currentEnd())).isEqualTo(pair.currentQuote());
            assertThat(pair.currentQuote()).contains("조회 기간에는 오늘 기안일이 포함됩니다.");
            assertThat(item.excerpt().substring(pair.sourceStart(), pair.sourceEnd())).isEqualTo(applicability);
        });
    }

    @Test void F009_CONTRACT_08_80건의_독립_질의에서_조건충돌과_정보부족을_강한추천으로_표시하지_않는다() throws Exception {
        var pack = mapper.readTree(Files.readString(Path.of("../../../docs/knowledge/packs/electronic-approval-v1/cases.json"))).path("cases");
        var queries = mapper.readTree(Files.readString(Path.of("../../../ai/evaluation/recommendations/electronic-approval-v1.json"))).path("queries");
        Map<String,JsonNode> cards = new HashMap<>(); pack.forEach(card -> cards.put(card.path("caseKey").asString(),card));
        assertThat(pack.size()).isEqualTo(80); assertThat(queries.size()).isEqualTo(80);
        for (var query : queries) {
            var card = cards.get(query.path("sourceCaseKey").asString());
            var evidence = evidence(card);
            var facts = new ArrayList<EvidenceStatementResponse>();
            var messageId = UUID.randomUUID();
            query.path("facts").forEach(f -> facts.add(new EvidenceStatementResponse(f.asString(),List.of(messageId),List.of())));
            var request = new StandardRequestResponse(UUID.randomUUID(),UUID.randomUUID(),1,"현재 문의",facts,List.of(new EvidenceStatementResponse("기안자: 추정값",List.of(),List.of())),List.of(),evidence.systemId(),List.of(),null,List.of());
            String message = String.join("\n",facts.stream().map(EvidenceStatementResponse::text).toList());
            var source = new RelatedMessageResponse(messageId,1,"USER",message,List.of(),Instant.now());
            var result = service.assess(evidence,request,List.of(source));
            if (query.path("mode").asString().equals("POSITIVE")) assertThat(result.relevance()).as(query.path("id").asString()).isEqualTo("HIGH");
            else assertThat(result.relevance()).as(query.path("id").asString()).isNotEqualTo("HIGH");
            assertThat(result.reasons().toString()).doesNotContain("추정값");
            for (var pair : result.quotePairs()) {
                assertThat(message.substring(pair.currentStart(),pair.currentEnd())).isEqualTo(pair.currentQuote());
                assertThat(evidence.excerpt().substring(pair.sourceStart(),pair.sourceEnd())).isEqualTo(pair.sourceQuote());
            }
            for (var section : result.sections()) assertThat(evidence.excerpt().substring(section.start(),section.end())).isEqualTo(section.quote());
        }
    }
    @Test void F009_CONTRACT_08_숫자_한도와_문서상태의_허용대안을_구분한다() {
        assertThat(ApplicabilityConditions.compare("21MB","20MB 초과")).isEqualTo("MATCHED");
        assertThat(ApplicabilityConditions.compare("20MB","20MB 초과")).isEqualTo("MISMATCHED");
        assertThat(ApplicabilityConditions.compare("11개월 전","3개월 초과 12개월 이내")).isEqualTo("MATCHED");
        assertThat(ApplicabilityConditions.compare("상신","상신 또는 결재 중")).isEqualTo("MATCHED");
        assertThat(ApplicabilityConditions.compare(null,"상신")).isEqualTo("UNKNOWN");
        assertThat(ApplicabilityConditions.current(List.of("조회 상태는 승인이에요.","조회 상태: 미확인"))).doesNotContainKey("조회 상태");
    }
    @Test void F009_CONTRACT_08_여러_문단인_조치를_검증결과로_잘못_나누지_않는다() {
        String action="첫 번째 조치\n\n두 번째 조치", verification="동일 문서에서 정상 결과 확인";
        String body="사례\n\n증상\n\n"+action+"\n\n"+verification+"\n\n적용 조건";
        var evidence=RecommendedCaseResponse.of(new KnowledgeEvidenceResponse(UUID.randomUUID(),UUID.randomUUID(),1,"CASE","사례",body,UUID.randomUUID(),null,"적용 조건","SYSTEM_STAFF","SYNTHETIC"),null);
        var request=new StandardRequestResponse(UUID.randomUUID(),UUID.randomUUID(),1,"문의",List.of(),List.of(),List.of(),UUID.randomUUID(),List.of(),null,List.of());
        var result=service.assess(evidence,request,List.of(),null,Map.of("title","사례","problemStatement","증상","action",action,"verification",verification));
        assertThat(result.sections()).anySatisfy(s->{assertThat(s.label()).isEqualTo("실제 조치");assertThat(s.quote()).isEqualTo(action);});
        assertThat(result.sections()).anySatisfy(s->{assertThat(s.label()).isEqualTo("검증 결과");assertThat(s.quote()).isEqualTo(verification);});
    }
    @Test void F009_CONTRACT_08_명시적인_미확인_기능을_분류_메타데이터로_확정하지_않는다() {
        UUID messageId=UUID.randomUUID();
        String message="기능: 미확인\n제목 길이: 120자\n상신 여부: 미완료";
        String conditions="기능: 문서 작성\n제목 길이: 120자\n상신 여부: 미완료";
        var evidence=RecommendedCaseResponse.of(new KnowledgeEvidenceResponse(UUID.randomUUID(),UUID.randomUUID(),1,"CASE","제목 길이",conditions,UUID.randomUUID(),null,conditions,"SYSTEM_STAFF","SYNTHETIC"),null);
        var request=new StandardRequestResponse(UUID.randomUUID(),UUID.randomUUID(),1,"문의",List.of(new EvidenceStatementResponse(message,List.of(messageId),List.of())),List.of(),List.of(),UUID.randomUUID(),List.of(),null,List.of());
        var result=service.assess(evidence,request,List.of(new RelatedMessageResponse(messageId,1,"USER",message,List.of(),Instant.now())),"문서 작성");
        assertThat(result.relevance()).isEqualTo("MEDIUM");
        assertThat(result.unknown()).isEqualTo(1);
        assertThat(result.quotePairs()).noneMatch(p->p.label().equals("기능"));
    }
    @Test void F009_CONTRACT_08_자유서술_승인조건도_원문에서_확인되는_현재조건만_비교한다() {
        UUID messageId=UUID.randomUUID();
        String message="제가 직접 상신한 문서예요. 조회 상태는 승인이에요.";
        String conditions="본인이 기안한 전자결재 문서이며 조회 상태가 승인인 경우.";
        var evidence=RecommendedCaseResponse.of(new KnowledgeEvidenceResponse(UUID.randomUUID(),UUID.randomUUID(),1,"CASE","문서 조회",conditions,UUID.randomUUID(),null,conditions,"SYSTEM_STAFF","SYNTHETIC"),null);
        var request=new StandardRequestResponse(UUID.randomUUID(),UUID.randomUUID(),1,"문의",List.of(new EvidenceStatementResponse(message,List.of(messageId),List.of())),List.of(),List.of(),UUID.randomUUID(),List.of(),null,List.of());
        var result=service.assess(evidence,request,List.of(new RelatedMessageResponse(messageId,1,"USER",message,List.of(),Instant.now())));
        assertThat(result.matched()).isEqualTo(2);
        assertThat(result.quotePairs()).extracting(p->p.label()).containsExactlyInAnyOrder("조회 상태","기안자");
        assertThat(result.conditions()).noneMatch(c->c.label().equals("문서 상태"));
        var guide=RecommendedCaseResponse.of(new KnowledgeEvidenceResponse(UUID.randomUUID(),UUID.randomUUID(),1,"GUIDE","기안부터 상신 완료까지",conditions,UUID.randomUUID(),null,"본인이 기안부터 상신 완료까지 진행하는 방법","EMPLOYEE","SYNTHETIC"),null);
        assertThat(service.assess(guide,request,List.of()).conditions()).isEmpty();
    }
    @Test void F009_CONTRACT_08_요약에서_빠진_조회조건을_허용된_문의자_원문으로_보완한다() {
        UUID messageId=UUID.randomUUID();
        String message="방금 제가 직접 상신한 전자결재 문서가 안 보여요. 조회 상태는 승인이에요. 제목 검색어는 비워 두었어요.";
        String conditions="조회 상태: 승인\n기안자: 본인\n상신 여부: 완료\n제목 검색어: 없음\n문서 상태: 상신";
        var evidence=RecommendedCaseResponse.of(new KnowledgeEvidenceResponse(UUID.randomUUID(),UUID.randomUUID(),1,"CASE","조회 조건",conditions,UUID.randomUUID(),null,conditions,"SYSTEM_STAFF","SYNTHETIC"),null);
        var request=new StandardRequestResponse(UUID.randomUUID(),UUID.randomUUID(),1,"문의",List.of(new EvidenceStatementResponse("문서함 목록에 안 보임",List.of(messageId),List.of())),List.of(),List.of(),UUID.randomUUID(),List.of(),null,List.of());
        var source=new RelatedMessageResponse(messageId,1,"USER",message,List.of(),Instant.now());
        var result=service.assess(evidence,request,List.of(source));
        assertThat(result.matched()).isEqualTo(4);
        assertThat(result.unknown()).isEqualTo(1);
        assertThat(result.quotePairs()).extracting(p->p.label()).contains("조회 상태","기안자","제목 검색어");
        assertThat(TicketCaseRecommendationService.contextQuery("문의",request,List.of(source))).contains("조회 상태는 승인이에요");
    }
    private static RecommendedCaseResponse evidence(JsonNode card) {
        var c=card.path("case"); var work=card.path("workLogs").path(1);
        String body=String.join("\n\n",c.path("title").asString(),c.path("problemStatement").asString(),work.path("action").asString(),work.path("verification").asString(),c.path("applicability").asString());
        return RecommendedCaseResponse.of(new KnowledgeEvidenceResponse(UUID.randomUUID(),UUID.randomUUID(),1,"CASE",c.path("title").asString(),body,UUID.randomUUID(),null,c.path("applicability").asString(),"SYSTEM_STAFF","SYNTHETIC"),null);
    }
}
