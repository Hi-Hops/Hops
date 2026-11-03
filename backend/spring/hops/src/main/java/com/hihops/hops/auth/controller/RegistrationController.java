package com.hihops.hops.auth.controller;

import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.hihops.hops.auth.dto.RegisterRequest;
import com.hihops.hops.auth.service.RegistrationService;

import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/auth")
@RequiredArgsConstructor
public class RegistrationController {

    private final RegistrationService registrationService;

    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "관리자의 일반 회원 등록")
    public RegistrationResponse register(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody RegisterRequest request) {
        return new RegistrationResponse(registrationService.register(jwt, request));
    }

    public record RegistrationResponse(UUID memberId) {
    }
}
