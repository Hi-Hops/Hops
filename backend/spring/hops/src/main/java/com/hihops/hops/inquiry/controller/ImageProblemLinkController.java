package com.hihops.hops.inquiry.controller;

import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.hihops.hops.global.idempotency.CommandReceipt;
import com.hihops.hops.global.idempotency.IdempotentRequest;
import com.hihops.hops.inquiry.dto.ImageProblemLinkCommand;
import com.hihops.hops.inquiry.service.MessageCommandService;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/inquiries/{inquiryId}/image-attachments/{attachmentId}/problem-links")
@ConditionalOnProperty(prefix = "hops.image-attachments", name = "enabled", havingValue = "true")
@RequiredArgsConstructor
class ImageProblemLinkController {
    private final MessageCommandService messages;

    @PostMapping
    ResponseEntity<CommandReceipt> update(@AuthenticationPrincipal Jwt jwt, HttpServletRequest http,
            @PathVariable UUID inquiryId, @PathVariable UUID attachmentId,
            @RequestHeader(value = IdempotentRequest.HEADER, required = false) @Nullable String key,
            @Valid @RequestBody ImageProblemLinkCommand command) {
        return messages.linkImageProblems(IdempotentRequest.of(jwt, http, key, command.requestId(), command),
                inquiryId, attachmentId, command).toResponseEntity();
    }
}
