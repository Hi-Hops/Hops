package com.hihops.hops.ticket.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import com.hihops.hops.knowledge.service.QueryEmbedding;

/** 실제 임베딩을 캐시해 재사용하는 격리 PostgreSQL·실제 추천 API 평가. 정답은 런타임 색인에 넣지 않는다. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles({"db","test"})
@Transactional
@EnabledIfEnvironmentVariable(named="HOPS_ASSISTANCE_EVAL_DIR",matches=".+")
class AssistanceRetrievalEvaluationTest {
    @Autowired JdbcTemplate db;
    @Autowired MockMvc mvc;
    @Autowired jakarta.persistence.EntityManager entities;
    @MockitoBean QueryEmbedding embedding;
    private final JsonMapper mapper=JsonMapper.builder().build();

    @Test void F009_CONTRACT_08_80질의의_검색_조건충돌_인용을_실제_API로_비교한다() throws Exception {
        Path dir=Path.of(System.getenv("HOPS_ASSISTANCE_EVAL_DIR"));
        var frames=mapper.readTree(Files.readString(dir.resolve("frames.json")));
        var vectors=mapper.readTree(Files.readString(dir.resolve("query-vectors.json")));
        for(String file:List.of("R__seed_dev_department_member.sql","R__seed_dev_it_system.sql")) {
            var resource=new ClassPathResource("db/seed/"+file);
            db.execute(new String(resource.getInputStream().readAllBytes(),java.nio.charset.StandardCharsets.UTF_8));
        }
        db.execute(Files.readString(dir.resolve("evaluation-index.sql")));
        when(embedding.embed(anyString())).thenAnswer(call -> {
            String input=call.getArgument(0);var vector=vectors.get(input);
            if(vector==null) throw new IllegalArgumentException("평가 임베딩 캐시에 없는 입력");
            return vector.asString();
        });
        var pack=mapper.readTree(Files.readString(Path.of("../../../docs/knowledge/packs/electronic-approval-v1/cases.json"))).path("cases");
        Map<String,JsonNode> corpus=new HashMap<>();
        for(var card:pack) {
            // DB의 승인 버전 ID를 읽어 정답 카탈로그에 대응한다. 미래 처리 내용을 질의에 넣지 않는다.
            String id=db.queryForObject("SELECT v.id::text FROM knowledge_document_version v JOIN knowledge_document d ON d.id=v.knowledge_document_id WHERE d.source_data->>'caseKey' = ?",String.class,card.path("caseKey").asString());
            corpus.put(id,card);
        }
        int positives=0,baselineHits=0,contextHits=0,wrongHigh=0,pairs=0;
        List<Map<String,Object>> violations=new ArrayList<>();
        List<Map<String,Object>> traces=new ArrayList<>(); List<Double> latencies=new ArrayList<>();
        for(var frame:frames) {
            UUID ticket=UUID.fromString(frame.path("ticketId").asString());
            var facts=new ArrayList<Map<String,Object>>();
            for(var fact:frame.path("facts")) facts.add(Map.of("text",fact.asString(),"sourceMessageIds",List.of(frame.path("messageId").asString()),"knowledgeEvidenceIds",List.of()));
            String title=frame.path("title").asString();
            db.update("UPDATE ticket SET title=? WHERE id=?",title,ticket);
            db.update("UPDATE standard_request SET summary=?,confirmed_facts=CAST(? AS jsonb),type_detail=CAST(? AS jsonb) WHERE id=CAST(? AS uuid)",title,mapper.writeValueAsString(facts),mapper.writeValueAsString(Map.of("taskOrScreen",frame.path("feature").asString(),"actualBehavior",title,"acceptanceCriteria",List.of())),frame.path("standardId").asString());
            String original=String.join("\n",facts.stream().map(f->f.get("text").toString()).toList());
            db.update("UPDATE message SET content=?,payload=CAST(? AS jsonb) WHERE id=CAST(? AS uuid)",original,mapper.writeValueAsString(Map.of("problemLinks",List.of(Map.of("problemId",db.queryForObject("SELECT inquiry_problem_id::text FROM ticket WHERE id=?",String.class,ticket),"revision",1,"spans",List.of(Map.of("start",0,"end",original.codePointCount(0,original.length()))))))),frame.path("messageId").asString());
            entities.clear();
            String owner=frame.path("system").asString().equals("COMMON_LOGIN")?"a0000000-0000-7000-8000-000000000012":"a0000000-0000-7000-8000-000000000011";
            var auth=jwt().jwt(token -> token.subject(owner).claim("departmentId",db.queryForObject("SELECT department_id::text FROM member WHERE id=CAST(? AS uuid)",String.class,owner)).claim("roles",List.of()).claim("policyRevision",1).claim("primarySystemIds",db.queryForList("SELECT id::text FROM it_system WHERE primary_owner_member_id=CAST(? AS uuid)",String.class,owner)).expiresAt(Instant.now().plusSeconds(600)));
            var baseline=mapper.readTree(mvc.perform(get("/api/v2/tickets/"+ticket+"/recommended-cases").with(auth)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
            long start=System.nanoTime();
            var result=mapper.readTree(mvc.perform(get("/api/v2/tickets/"+ticket+"/assistance").with(auth)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
            latencies.add((System.nanoTime()-start)/1_000_000.0);
            assertThat(result.path("query").asString()).isEqualTo(frame.path("context").asString());
            String expected=frame.path("expectedCaseFamily").asString();
            boolean base=false,improved=false;
            for(var item:baseline) if(corpus.containsKey(item.path("id").asString()) && corpus.get(item.path("id").asString()).path("pack").path("family").asString().equals(expected)) base=true;
            for(var material:result.path("materials")) {
                var evidence=material.path("evidence");var assessment=material.path("assessment");
                if(evidence.path("kind").asString().equals("CASE") && assessment.path("family").asString().equals(expected) && !assessment.path("relevance").asString().equals("CONFLICT")) improved=true;
                if(assessment.path("relevance").asString().equals("HIGH") && evidence.path("kind").asString().equals("CASE")) {
                    var candidate=corpus.get(evidence.path("id").asString());
                    if(candidate!=null) for(var condition:candidate.path("pack").path("conditions").properties()) {
                        if(!condition.getValue().asString().equals(frame.path("expectedConditions").path(condition.getKey()).asString())) {
                            wrongHigh++;
                            violations.add(Map.of("queryId",frame.path("id").asString(),"caseKey",candidate.path("caseKey").asString(),"field",condition.getKey(),"expected",frame.path("expectedConditions"),"assessment",assessment));
                            break;
                        }
                    }
                }
                for(var pair:assessment.path("quotePairs")) {
                    assertThat(original.substring(pair.path("currentStart").asInt(),pair.path("currentEnd").asInt())).isEqualTo(pair.path("currentQuote").asString());
                    assertThat(evidence.path("excerpt").asString().substring(pair.path("sourceStart").asInt(),pair.path("sourceEnd").asInt())).isEqualTo(pair.path("sourceQuote").asString());pairs++;
                }
            }
            if(frame.path("mode").asString().equals("POSITIVE")){positives++;if(base)baselineHits++;if(improved)contextHits++;}
            traces.add(Map.of("id",frame.path("id").asString(),"split",frame.path("split").asString(),"mode",frame.path("mode").asString(),"baselineHit",base,"contextHit",improved,"materials",result.path("materials").size()));
        }
        Collections.sort(latencies);
        Map<String,Object> report=Map.of("queries",frames.size(),"positiveQueries",positives,"baselineRecallAt5",baselineHits/(double)positives,"contextRecallAt3",contextHits/(double)positives,"incorrectHigh",wrongHigh,"verifiedQuotePairs",pairs,"p50Ms",latencies.get(latencies.size()/2),"p95Ms",latencies.get((int)(latencies.size()*.95)),"traces",traces);
        var completeReport=new LinkedHashMap<String,Object>(report);
        completeReport.put("violations",violations);
        Files.writeString(dir.resolve("retrieval-report.json"),mapper.writerWithDefaultPrettyPrinter().writeValueAsString(completeReport));
        assertThat(contextHits/(double)positives).isGreaterThanOrEqualTo(.9);
        assertThat(wrongHigh).isZero(); assertThat(pairs).isPositive();
    }
}
