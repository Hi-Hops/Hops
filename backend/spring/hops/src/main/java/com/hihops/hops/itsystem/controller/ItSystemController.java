package com.hihops.hops.itsystem.controller;

import java.util.UUID;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.hihops.hops.itsystem.dto.PrimaryAssignmentResponse;
import com.hihops.hops.itsystem.service.ItSystemService;

import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/systems")
@RequiredArgsConstructor
public class ItSystemController {
    private final ItSystemService systems;

    @GetMapping("/{systemId}/primary")
    public PrimaryAssignmentResponse primary(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID systemId) {
        return systems.primary(jwt, systemId);
    }
}
