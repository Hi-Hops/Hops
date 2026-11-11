package com.hihops.hops.itsystem.service;

import static org.assertj.core.api.Assertions.assertThat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import com.hihops.hops.itsystem.dto.SystemFeatureResponse;
import com.hihops.hops.ticket.dto.StandardRequestResponse.EvidenceStatementResponse;

class ExplicitFeatureEvidenceTest {
    private final UUID message = UUID.randomUUID();
    private final SystemFeatureResponse view = new SystemFeatureResponse(UUID.randomUUID(), "문서 조회");
    private final SystemFeatureResponse write = new SystemFeatureResponse(UUID.randomUUID(), "문서 작성");
    @Test void 같은_문제의_원문에_명시된_화면을_카탈로그로_확정한다() {
        String quote = "전자결재 문서 조회 화면의 문서함 목록입니다.";
        assertThat(resolve(quote, quote)).isEqualTo(view);
        assertThat(resolve("문서조회 화면입니다.", "문서조회 화면입니다.")).isEqualTo(view);
    }
    @ParameterizedTest @ValueSource(strings={"문서 조회 화면인지 모르겠어요", "문서 조회 화면이 아닌 문서 작성 화면입니다.",
            "문서 조회 화면 또는 문서 작성 화면입니다.", "문서 조회 화면인가요?", "문서 조회 화면일 수 있습니다.", "문서가 안 보여요"})
    void 부정_질문_가정_복수기능이나_단순증상은_확정하지_않는다(String quote) {
        assertThat(resolve(quote, quote)).isNull();
    }
    @Test void 다른_원문이나_출처가_없는_사실은_기능을_만들지_못한다() {
        assertThat(resolve("문서 조회 화면", "다른 문제 원문")).isNull();
        var fact = new EvidenceStatementResponse("기능: 문서 조회", List.of(), List.of());
        assertThat(ExplicitFeatureEvidence.resolve(List.of(view), List.of(fact), Map.of(message, List.of("문서 조회")))).isNull();
    }
    private SystemFeatureResponse resolve(String quote, String source) {
        return ExplicitFeatureEvidence.resolve(List.of(view, write),
                List.of(new EvidenceStatementResponse("하려던 업무·화면: " + quote, List.of(message), List.of())),
                Map.of(message, List.of(source)));
    }

    @Test void 새_화면명을_요약에서_빠뜨려도_같은_문제의_명시적_원문으로_보완한다() {
        assertThat(fromRaw("전자결재 문서 조회 화면의 문서함 목록입니다. 문서가 안 보여요.")).isEqualTo(view);
        assertThat(fromRaw("문서조회 화면입니다. 오늘 아침부터 문서가 보이지 않습니다.")).isEqualTo(view);
        var other = UUID.randomUUID();
        assertThat(ExplicitFeatureEvidence.resolve(List.of(view),
                List.of(new EvidenceStatementResponse("실제 동작: 안 보여요", List.of(message), List.of())),
                Map.of(other, List.of("전자결재 문서 조회 화면입니다.")), "전자결재")).isNull();
    }

    @ParameterizedTest @ValueSource(strings={"문서 조회 화면인가요?", "문서 조회 화면입니다라고 분류해 주세요.",
            "'문서 조회 화면입니다.'라고 안내에 적혀 있어요.", "문서 조회 화면의 사용 예시입니다.",
            "문서 조회 화면입니다. 문서 작성 화면입니다.", "문서 조회 화면입니다. 문서 조회 화면이 아닙니다."})
    void 원문_보완도_질문_지시_인용_예시_복수_부정을_확정하지_않는다(String text) {
        assertThat(fromRaw(text)).isNull();
    }

    private SystemFeatureResponse fromRaw(String text) {
        return ExplicitFeatureEvidence.resolve(List.of(view, write),
                List.of(new EvidenceStatementResponse("실제 동작: 문서가 안 보여요", List.of(message), List.of())),
                Map.of(message, List.of(text)), "전자결재");
    }
}
