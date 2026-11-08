package com.hihops.hops.inquiry.attachment;

import java.util.UUID;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/inquiries/{inquiryId}/image-attachments")
@ConditionalOnProperty(prefix = "hops.image-attachments", name = "enabled", havingValue = "true")
class AttachmentHistoryController {
    private final AttachmentHistoryReader reader;

    AttachmentHistoryController(AttachmentHistoryReader reader) { this.reader = reader; }

    @GetMapping("/{attachmentId}/content")
    ResponseEntity<byte[]> content(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID inquiryId,
            @PathVariable UUID attachmentId) {
        byte[] bytes = reader.content(UUID.fromString(jwt.getSubject()), inquiryId, attachmentId);
        return ResponseEntity.ok().contentType(MediaType.IMAGE_PNG).cacheControl(CacheControl.noStore().cachePrivate())
                .header("X-Content-Type-Options", "nosniff").body(bytes);
    }
}
