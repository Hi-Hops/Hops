package com.hihops.hops.inquiry.attachment;

import java.util.List;
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

import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/tickets/{ticketId}/image-attachments")
@ConditionalOnProperty(prefix = "hops.image-attachments", name = "enabled", havingValue = "true")
@RequiredArgsConstructor
class StaffImageController {
    private final StaffImageContentService content;

    @GetMapping
    ResponseEntity<List<StaffImageAccess.AttachmentRef>> list(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID ticketId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore().cachePrivate()).body(content.list(jwt, ticketId));
    }

    @GetMapping("/{attachmentId}/content")
    ResponseEntity<byte[]> content(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID ticketId,
            @PathVariable UUID attachmentId) {
        return ResponseEntity.ok().contentType(MediaType.IMAGE_PNG).cacheControl(CacheControl.noStore().cachePrivate())
                .header("X-Content-Type-Options", "nosniff").body(content.content(jwt, ticketId, attachmentId));
    }
}
