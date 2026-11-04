package com.hihops.hops.knowledge.service;
import java.util.UUID;
import org.springframework.security.oauth2.jwt.Jwt;
public record CaseIndexRequested(UUID caseId, String store, Jwt actor, boolean check) {}
