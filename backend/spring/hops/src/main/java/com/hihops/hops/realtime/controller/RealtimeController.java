package com.hihops.hops.realtime.controller;

import java.util.UUID;

import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import com.hihops.hops.realtime.dto.RealtimeTarget;
import com.hihops.hops.realtime.service.RealtimeService;

@RestController
public class RealtimeController {
    private final RealtimeService realtime;

    public RealtimeController(RealtimeService realtime) {
        this.realtime = realtime;
    }

    @GetMapping("/api/realtime/events")
    public ResponseEntity<SseEmitter> events(@AuthenticationPrincipal Jwt jwt,
            @RequestParam RealtimeTarget.Type targetType, @RequestParam UUID targetId,
            @RequestHeader(value = "Last-Event-ID", required = false) String lastEventId) {
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("text/event-stream;charset=UTF-8"))
                .cacheControl(CacheControl.noStore())
                .body(realtime.connect(jwt, new RealtimeTarget(targetType, targetId), lastEventId));
    }
}
