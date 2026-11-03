package com.hihops.hops.ticket.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** inquiryId·sequenceNo·다른 문제 링크는 공개하지 않는다. content와 revisions는 허용 구간을 투영한 값만 받는다. */
public record RelatedMessageResponse(UUID id, int revision, String sender, String content,
        List<RevisionResponse> revisions, Instant createdAt) {
    public record RevisionResponse(int revisionNo, String content, Instant createdAt) {
    }
}
