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

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** 후속 메시지 입력. 첨부 목록 생략은 기존 요청과 같으며 명시적 null은 허용하지 않는다. */
@JsonPropertyOrder({"requestId", "expectedInquiryVersion", "content", "problemIds", "attachmentIds"})
public class MessageSendCommand {
    @NotNull private UUID requestId;
    @NotNull @Min(1) @Max(Integer.MAX_VALUE) private Integer expectedInquiryVersion;
    @NotNull @UserContent private String content;
    @NotNull private List<@NotNull UUID> problemIds;
    @Size(max = 5) private @Nullable List<@NotNull UUID> attachmentIds;

    @JsonCreator public MessageSendCommand() { }

    public MessageSendCommand(UUID requestId, Integer expectedInquiryVersion, String content,
            List<UUID> problemIds, @Nullable List<UUID> attachmentIds) {
        this.requestId = requestId;
        this.expectedInquiryVersion = expectedInquiryVersion;
        this.content = content;
        this.problemIds = problemIds;
        this.attachmentIds = attachmentIds;
    }

    @JsonProperty public UUID requestId() { return requestId; }
    @JsonProperty public Integer expectedInquiryVersion() { return expectedInquiryVersion; }
    @JsonProperty public String content() { return content; }
    @JsonProperty public List<UUID> problemIds() { return problemIds; }
    @JsonProperty @JsonInclude(JsonInclude.Include.NON_EMPTY)
    public @Nullable List<UUID> attachmentIds() { return attachmentIds; }

    @JsonSetter public void setRequestId(UUID requestId) { this.requestId = requestId; }
    @JsonSetter public void setExpectedInquiryVersion(Integer version) { this.expectedInquiryVersion = version; }
    @JsonSetter public void setContent(String content) { this.content = content; }
    @JsonSetter public void setProblemIds(List<UUID> problemIds) { this.problemIds = problemIds; }
    @JsonSetter(nulls = Nulls.FAIL) public void setAttachmentIds(List<UUID> attachmentIds) { this.attachmentIds = attachmentIds; }
}
