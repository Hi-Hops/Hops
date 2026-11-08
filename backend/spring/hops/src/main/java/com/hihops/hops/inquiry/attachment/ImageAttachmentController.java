package com.hihops.hops.inquiry.attachment;

import java.io.IOException;
import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.hihops.hops.global.exception.BusinessException;
import com.hihops.hops.global.exception.ErrorCode;
import com.hihops.hops.global.idempotency.CommandReceipt;
import com.hihops.hops.global.idempotency.IdempotentRequest;

import jakarta.servlet.http.HttpServletRequest;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

@RestController
@RequestMapping("/image-attachments")
@ConditionalOnProperty(prefix = "hops.image-attachments", name = "enabled", havingValue = "true")
class ImageAttachmentController {
    private static final int MAX_REQUEST_BYTES = 7 * 1024 * 1024;
    private final ImageAttachmentService service;
    private final JsonMapper mapper;

    ImageAttachmentController(ImageAttachmentService service, JsonMapper mapper) {
        this.service = service;
        this.mapper = mapper;
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<CommandReceipt> upload(@AuthenticationPrincipal Jwt jwt, HttpServletRequest http,
            @RequestHeader(value = IdempotentRequest.HEADER, required = false) @Nullable String key) throws IOException {
        if (http.getContentLengthLong() > MAX_REQUEST_BYTES) throw new BusinessException(ErrorCode.PAYLOAD_TOO_LARGE);
        byte[] body = http.getInputStream().readNBytes(MAX_REQUEST_BYTES + 1);
        if (body.length > MAX_REQUEST_BYTES) throw new BusinessException(ErrorCode.PAYLOAD_TOO_LARGE);
        UploadCommand command;
        try {
            command = mapper.readValue(body, UploadCommand.class);
        } catch (JacksonException e) {
            throw new BusinessException(ErrorCode.INVALID_INPUT);
        }
        if (command == null) throw new BusinessException(ErrorCode.INVALID_INPUT);
        IdempotentRequest request = IdempotentRequest.of(jwt, http, key, command.requestId(), "");
        return service.upload(request, command.mediaType(), command.dataBase64()).toResponseEntity();
    }

    @GetMapping("/{attachmentId}")
    ImageAttachmentService.AttachmentMetadata metadata(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID attachmentId) {
        return service.findOwned(UUID.fromString(jwt.getSubject()), attachmentId);
    }

    record UploadCommand(UUID requestId, String mediaType, String dataBase64) { }
}
