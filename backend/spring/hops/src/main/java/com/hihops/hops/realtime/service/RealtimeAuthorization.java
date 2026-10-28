package com.hihops.hops.realtime.service;

import java.time.Instant;
import java.util.HashSet;
import java.util.UUID;

import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.hihops.hops.department.repository.DepartmentRepository;
import com.hihops.hops.global.exception.BusinessException;
import com.hihops.hops.global.exception.ErrorCode;
import com.hihops.hops.realtime.dto.RealtimeTarget;
import com.hihops.hops.user.domain.Member;
import com.hihops.hops.user.domain.Role;
import com.hihops.hops.user.repository.MemberRepository;

@Service
public class RealtimeAuthorization {
    private final MemberRepository members;
    private final DepartmentRepository departments;

    public RealtimeAuthorization(MemberRepository members, DepartmentRepository departments) {
        this.members = members;
        this.departments = departments;
    }

    @Transactional(readOnly = true)
    public void check(Jwt jwt, RealtimeTarget target) {
        if (jwt.getExpiresAt() == null || !Instant.now().isBefore(jwt.getExpiresAt())) {
            throw new BusinessException(ErrorCode.UNAUTHENTICATED);
        }
        UUID memberId;
        try {
            memberId = UUID.fromString(jwt.getSubject());
        } catch (IllegalArgumentException | NullPointerException exception) {
            throw new BusinessException(ErrorCode.UNAUTHENTICATED);
        }
        Member member = members.findWithDepartmentAndRolesByIdAndDeletedAtIsNull(memberId)
                .orElseThrow(() -> new BusinessException(ErrorCode.UNAUTHENTICATED));
        if (member.getDepartment().isDeleted()
                || !member.getDepartment().getId().toString().equals(jwt.getClaimAsString("departmentId"))
                || jwt.getClaimAsStringList("roles") == null
                || !new HashSet<>(member.getRoles().stream().map(Role::name).toList())
                        .equals(new HashSet<>(jwt.getClaimAsStringList("roles")))) {
            throw new BusinessException(ErrorCode.UNAUTHENTICATED);
        }
        // 해당 도메인의 소유권 조회가 구현되기 전에는 ID만으로 연결을 허용하지 않는다.
        if (target.type() != RealtimeTarget.Type.DEPARTMENT_TICKETS) {
            throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE);
        }
        if (!departments.existsByIdAndDeletedAtIsNull(target.id())) {
            throw new BusinessException(ErrorCode.NOT_FOUND);
        }
        if (!member.getDepartment().getId().equals(target.id()) || !member.getDepartment().isRoutable()) {
            if (!member.getRoles().contains(Role.BIZ_ADMIN)) {
                throw new BusinessException(ErrorCode.ACCESS_DENIED);
            }
        }
    }
}
