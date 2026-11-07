package com.hihops.hops.agent.service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** AI가 티켓 도구(내부 API)로 보내는 생성 요청 본문을 만드는 테스트용 도우미. */
final class TicketCreationTestBodies {

    static final String TOKEN = "dGVzdC10b2tlbi1mb3ItdGlja2V0LWNyZWF0aW9uLWFwaQ";

    private TicketCreationTestBodies() {
    }

    /**
     * 이미 저장된 문제(problemRef=문제 UUID)에 대한 담당자 검토 티켓 생성 요청. 사실은 그 문제에 연결된 사용자 메시지를 출처로 한다.
     *
     * @param inquiryVersion 접수한 실행의 입력 버전
     * @param sourceLength   출처 메시지 내용의 코드 포인트 수. 메시지 전체를 이 문제에 잇는다(이미 이어져 있으면 그대로 둔다)
     */
    /** 이미 확인한 사실의 문구. 저장된 요청서의 사실과 같아야 한다(기존 확인 사실을 누락하면 거부된다). */
    static final String FACT = "결재 문서가 열리지 않는다";

    static Map<String, Object> body(String problemRef, int inquiryVersion, UUID system, UUID sourceMessage,
            int sourceLength) {
        return body(problemRef, inquiryVersion, system, sourceMessage, sourceLength, "OBSERVED_SYMPTOM",
                "INVESTIGATION");
    }

    static Map<String, Object> body(String problemRef, int inquiryVersion, UUID system, UUID sourceMessage,
            int sourceLength, String intent, String processingType) {
        var details = new LinkedHashMap<String, Object>();
        for (String key : List.of("taskOrScreen", "requestedAccess", "businessPurpose", "expectedBehavior",
                "actualBehavior", "occurredAt", "currentWorkflow", "desiredChange")) {
            details.put(key, null);
        }
        details.put("actualBehavior", "결재 문서가 열리지 않는다");
        details.put("acceptanceCriteria", List.of());
        var facts = List.of(Map.of("text", FACT, "sourceMessageIds", List.of(sourceMessage),
                "knowledgeEvidenceIds", List.of()));
        var problem = Map.of("problemRef", problemRef, "summary", "결재 문서 열람 오류", "intent", intent,
                "processingType", processingType, "reportedSystemCandidateId", system, "facts", facts,
                "assumptions", List.of(), "unknowns", List.of("발생 시점"), "details", details);
        var snapshot = Map.of("summary", "결재 문서 열람 오류", "confirmedFacts", facts, "assumptions", List.of(),
                "unknowns", List.of("발생 시점"), "responsibleSystemCandidateId", system,
                "responsibilityEvidenceIds", List.of(), "details", details, "knowledge", List.of());
        var proposal = Map.of("problemRef", problemRef, "title", "결재 문서 열람 오류", "processingType", processingType,
                "responsibleSystemCandidateId", system, "evidenceIds", List.of(), "standardRequestSnapshot", snapshot);
        return new LinkedHashMap<>(Map.of("inquiryVersion", inquiryVersion, "problem", problem, "proposal", proposal,
                "messageLinks", List.of(Map.of("messageId", sourceMessage, "revision", 1, "spans",
                        List.of(Map.of("start", 0, "end", sourceLength))))));
    }
}
