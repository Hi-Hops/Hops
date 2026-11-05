package com.hihops.hops.knowledge.dto;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.hihops.hops.ticket.dto.CommandInput;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import tools.jackson.databind.JsonNode;

/**
 * 사례 초안 수정 요청 (DATA_TYPES CaseEditCommand). 출처·원 기록·적용 시스템·확인된 원인은 이 요청으로 바꾸지 않는다.
 *
 * <p>모든 키가 있어야 하고, appliesToSystemVersion만 null(미확인)이 허용된다. 공백 문자열은 받지 않는다.
 * limitations는 문자열만 담은 배열이어야 한다. 숫자·불리언이 문자열로 바뀌어 저장되지 않도록 값 그대로 검사한다.
 */
public record CaseEditCommand(@NotNull UUID requestId, @NotNull @Min(1) Integer expectedCaseVersion,
        String problemStatement, String title, String action, String verification, String applicability,
        JsonNode limitations, @JsonProperty(required = true) String appliesToSystemVersion,
        @NotNull AccessScope accessScope) {

    /** 공개 범위. 저장값은 STAFF가 아니라 계약 이름 SYSTEM_STAFF를 쓴다. */
    public enum AccessScope { EMPLOYEE, SYSTEM_STAFF }

    public CaseEditCommand {
        CommandInput.text(problemStatement, Integer.MAX_VALUE, false);
        CommandInput.text(title, 200, false);
        CommandInput.text(action, Integer.MAX_VALUE, false);
        CommandInput.text(verification, Integer.MAX_VALUE, false);
        CommandInput.text(applicability, Integer.MAX_VALUE, false);
        CommandInput.text(appliesToSystemVersion, 100, true);
        if (limitations == null || !limitations.isArray()) {
            throw new IllegalArgumentException("limitations는 문자열 배열이어야 합니다");
        }
        for (JsonNode item : limitations) {
            if (!item.isString()) {
                throw new IllegalArgumentException("limitations는 문자열만 담을 수 있습니다");
            }
            CommandInput.text(item.asString(), Integer.MAX_VALUE, false);
        }
    }

    /** 검증을 마친 제한 조건 목록 */
    public List<String> limitationList() {
        List<String> result = new ArrayList<>();
        for (JsonNode item : limitations) {
            result.add(item.asString());
        }
        return List.copyOf(result);
    }
}
