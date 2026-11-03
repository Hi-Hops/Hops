package com.hihops.hops.auth.service;

import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.UUID;

import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.hihops.hops.auth.dto.RegisterRequest;
import com.hihops.hops.department.domain.Department;
import com.hihops.hops.department.repository.DepartmentRepository;
import com.hihops.hops.global.exception.BusinessException;
import com.hihops.hops.global.exception.ErrorCode;
import com.hihops.hops.user.domain.Member;
import com.hihops.hops.user.domain.Role;
import com.hihops.hops.user.repository.MemberRepository;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class RegistrationService {

    private final MemberRepository members;
    private final DepartmentRepository departments;
    private final PasswordEncoder passwordEncoder;

    @Transactional
    public UUID register(Jwt jwt, RegisterRequest request) {
        UUID actorId;
        try {
            actorId = UUID.fromString(jwt.getSubject());
        } catch (IllegalArgumentException | NullPointerException exception) {
            throw new BusinessException(ErrorCode.UNAUTHENTICATED);
        }
        Member actor = members.findWithDepartmentAndRolesByIdAndDeletedAtIsNull(actorId)
                .orElseThrow(() -> new BusinessException(ErrorCode.UNAUTHENTICATED));
        if (jwt.getClaimAsString("departmentId") == null || jwt.getClaimAsStringList("roles") == null) {
            throw new BusinessException(ErrorCode.UNAUTHENTICATED);
        }
        if (actor.getDepartment().isDeleted()
                || !actor.getDepartment().getId().toString().equals(jwt.getClaimAsString("departmentId"))
                || !new HashSet<>(Role.contractRoles(actor.getRoles()).stream().map(Role::name).toList())
                        .equals(new HashSet<>(jwt.getClaimAsStringList("roles")))) {
            throw new BusinessException(ErrorCode.AUTH_CONTEXT_CHANGED);
        }
        if (!actor.getRoles().contains(Role.BIZ_ADMIN)) {
            throw new BusinessException(ErrorCode.ACCESS_DENIED);
        }
        // BCrypt은 입력을 최대 72바이트까지만 처리하므로 같은 해시가 되는 긴 입력을 허용하지 않는다.
        if (request.password().getBytes(StandardCharsets.UTF_8).length > 72) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "비밀번호는 UTF-8 기준 72바이트 이하여야 합니다.");
        }
        Department department = departments.findByIdAndDeletedAtIsNull(request.departmentId())
                .orElseThrow(() -> new BusinessException(ErrorCode.INVALID_INPUT, "유효한 부서를 선택해 주세요."));
        if (members.existsByLoginIdOrEmployeeNo(request.loginId(), request.employeeNo())) {
            throw duplicateMember();
        }
        Member member = new Member(request.loginId(), request.employeeNo(),
                passwordEncoder.encode(request.password()), request.name(), department);
        try {
            return members.saveAndFlush(member).getId();
        } catch (DataIntegrityViolationException exception) {
            // 동시 요청은 사전 중복 확인을 함께 통과할 수 있다. DB의 고유 제약이 최종 보장한다.
            Throwable cause = exception;
            while (cause != null) {
                if (cause instanceof ConstraintViolationException violation
                        && ("uk_member_login_id".equals(violation.getConstraintName())
                        || "uk_member_employee_no".equals(violation.getConstraintName()))) {
                    throw duplicateMember();
                }
                cause = cause.getCause();
            }
            throw exception;
        }
    }

    private static BusinessException duplicateMember() {
        return new BusinessException(ErrorCode.CONFLICT, "이미 사용 중인 로그인 ID 또는 사번입니다.");
    }
}
