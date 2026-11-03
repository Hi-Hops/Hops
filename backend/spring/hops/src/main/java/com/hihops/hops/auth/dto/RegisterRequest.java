package com.hihops.hops.auth.dto;

import java.util.UUID;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record RegisterRequest(
        @NotBlank @Size(max = 100) String loginId,
        @NotBlank @Size(max = 100) String employeeNo,
        @NotBlank String password,
        @NotBlank @Size(max = 50) String name,
        @NotNull UUID departmentId) {
}
