package com.hihops.hops.agent.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.hihops.hops.agent.dto.AiRunRequest.AccessContext;
import com.hihops.hops.agent.dto.AiRunRequest.ExecutionLimits;
import com.hihops.hops.agent.service.AnalysisRunProperties.InternalJudgement;
import com.hihops.hops.inquiry.domain.Inquiry;
import com.hihops.hops.user.domain.Member;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 상담 실행 입력의 접근 문맥. 기본 프로필(내부 판단 경계 꺼짐)은 직원 공개 범위만 싣고 retrievalScopes 키가 없다.
 * 옵션은 서버 설정이 켰을 때만 retrievalScopes를 싣는다. 요청·모델에서 값을 받는 경로가 없다.
 */
class AnalysisInputFactoryAccessTest {
    private final JsonMapper mapper = JsonMapper.builder().build();
    private final UUID requesterId = UUID.randomUUID();
    private final UUID systemId = UUID.randomUUID();

    private AnalysisInputFactory factory(boolean internalJudgement) {
        var properties = new AnalysisRunProperties(Duration.ofSeconds(60), new ExecutionLimits(8, 60000, 12000, 10, 2),
                "pending", "pending", "hops-system-1", new InternalJudgement(internalJudgement));
        return new AnalysisInputFactory(properties, mapper);
    }

    private AccessContext access(boolean internalJudgement) {
        Inquiry inquiry = mock(Inquiry.class);
        Member requester = mock(Member.class);
        when(requester.getId()).thenReturn(requesterId);
        when(requester.getPolicyRevision()).thenReturn(3);
        when(inquiry.getRequester()).thenReturn(requester);
        var systems = List.of(new com.hihops.hops.itsystem.dto.SupportedSystemResponse(systemId,
                com.hihops.hops.itsystem.dto.SystemCode.COMMON_LOGIN, "공통 로그인", List.of()));
        return factory(internalJudgement).accessOf(inquiry, systems);
    }

    @Test
    void 기본_프로필은_직원_공개_범위만_싣고_retrievalScopes_키가_없다() {
        AccessContext context = access(false);
        JsonNode json = mapper.valueToTree(context);

        assertThat(context.purpose()).isEqualTo("EMPLOYEE_HELP");
        assertThat(context.allowedDocumentScopes()).containsExactly("EMPLOYEE");
        assertThat(context.retrievalScopes()).isNull();
        assertThat(json.has("retrievalScopes")).isFalse();
        assertThat(json.path("allowedDocumentScopes").size()).isEqualTo(1);
        assertThat(json.path("policyRevision").asInt()).isEqualTo(3);
    }

    @Test
    void 서버_설정이_켜졌을_때만_retrievalScopes를_싣고_사용자_범위는_그대로다() {
        AccessContext context = access(true);
        JsonNode json = mapper.valueToTree(context);

        assertThat(context.allowedDocumentScopes()).containsExactly("EMPLOYEE");
        assertThat(context.retrievalScopes()).containsExactly("EMPLOYEE", "SYSTEM_STAFF");
        assertThat(json.path("retrievalScopes").valueStream().map(JsonNode::asString).toList()).containsExactly("EMPLOYEE", "SYSTEM_STAFF");
        // 사용자에게 닿을 수 있는 범위(allowedDocumentScopes)는 옵션이 켜져도 넓어지지 않는다.
        assertThat(json.path("allowedDocumentScopes").valueStream().map(JsonNode::asString).toList()).containsExactly("EMPLOYEE");
    }
}
