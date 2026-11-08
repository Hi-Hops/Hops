package com.hihops.hops.inquiry.dto;

import java.util.List;
import java.util.UUID;

import org.jspecify.annotations.Nullable;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import com.fasterxml.jackson.annotation.JsonSetter;
import com.fasterxml.jackson.annotation.Nulls;
import com.hihops.hops.global.validation.UserContent;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** 첫 메시지 입력. 첨부 목록 생략은 기존 요청과 같으며 명시적 null은 허용하지 않는다. */
@JsonPropertyOrder({"requestId", "content", "reportedSystemId", "attachmentIds"})
public class InquiryCreateCommand {
    @NotNull private UUID requestId;
    @NotNull @UserContent private String content;
    private @Nullable UUID reportedSystemId;
    @Size(max = 5) private @Nullable List<@NotNull UUID> attachmentIds;

    @JsonCreator public InquiryCreateCommand() { }

    public InquiryCreateCommand(UUID requestId, String content, @Nullable UUID reportedSystemId,
            @Nullable List<UUID> attachmentIds) {
        this.requestId = requestId;
        this.content = content;
        this.reportedSystemId = reportedSystemId;
        this.attachmentIds = attachmentIds;
    }

    @JsonProperty public UUID requestId() { return requestId; }
    @JsonProperty public String content() { return content; }
    @JsonProperty public @Nullable UUID reportedSystemId() { return reportedSystemId; }
    @JsonProperty @JsonInclude(JsonInclude.Include.NON_EMPTY)
    public @Nullable List<UUID> attachmentIds() { return attachmentIds; }

    @JsonSetter public void setRequestId(UUID requestId) { this.requestId = requestId; }
    @JsonSetter public void setContent(String content) { this.content = content; }
    @JsonSetter public void setReportedSystemId(@Nullable UUID reportedSystemId) { this.reportedSystemId = reportedSystemId; }
    @JsonSetter(nulls = Nulls.FAIL) public void setAttachmentIds(List<UUID> attachmentIds) { this.attachmentIds = attachmentIds; }
}
