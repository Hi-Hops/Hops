package com.hihops.hops.knowledge.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.Pattern;

/** GET /cases/{caseId} 조건. caseVersion은 선택이며 현재 버전만 허용한다(과거 버전은 404). */
public record CaseDetailRequest(@Pattern(regexp = "[1-9][0-9]*") @DecimalMax("2147483647") String caseVersion) {

    public Integer requestedVersion() {
        return caseVersion == null ? null : Integer.valueOf(caseVersion);
    }
}
