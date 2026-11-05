package com.hihops.hops.knowledge.dto;

import java.util.List;

/** CaseDetail (DATA_TYPES 신규 절). DRAFT에는 approval=null·indexes=[]다. */
public record CaseDetailResponse(CaseVersionResponse version, CaseApprovalResponse approval,
        List<IndexResultResponse> indexes, PublicationResponse publication) {
}
