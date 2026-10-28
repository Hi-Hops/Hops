package com.hihops.hops.realtime.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;

import com.hihops.hops.department.domain.Department;
import com.hihops.hops.department.repository.DepartmentRepository;
import com.hihops.hops.global.exception.BusinessException;
import com.hihops.hops.global.exception.ErrorCode;
import com.hihops.hops.realtime.dto.RealtimeTarget;
import com.hihops.hops.user.domain.Member;
import com.hihops.hops.user.domain.Role;
import com.hihops.hops.user.repository.MemberRepository;

class RealtimeAuthorizationTest {
    private final MemberRepository members = mock(MemberRepository.class);
    private final DepartmentRepository departments = mock(DepartmentRepository.class);
    private final RealtimeAuthorization authorization = new RealtimeAuthorization(members, departments);
    private final UUID userId = UUID.randomUUID();
    private final UUID departmentId = UUID.randomUUID();
    private final Member member = mock(Member.class);
    private final Department department = mock(Department.class);

    @BeforeEach
    void setup() {
        when(members.findWithDepartmentAndRolesByIdAndDeletedAtIsNull(userId)).thenReturn(Optional.of(member));
        when(member.getDepartment()).thenReturn(department);
        when(member.getRoles()).thenReturn(List.of());
        when(department.getId()).thenReturn(departmentId);
        when(department.isRoutable()).thenReturn(true);
        when(departments.existsByIdAndDeletedAtIsNull(departmentId)).thenReturn(true);
    }

    @Test
    void 현재_처리부서_구성원은_자기_부서에_연결한다() {
        authorization.check(token(List.of()), target(departmentId));
    }

    @Test
    void 다른_부서원은_거부한다() {
        UUID other = UUID.randomUUID();
        when(departments.existsByIdAndDeletedAtIsNull(other)).thenReturn(true);
        denied(token(List.of()), target(other), ErrorCode.ACCESS_DENIED);
    }

    @Test
    void 업무관리자는_다른_실제_부서에_연결한다() {
        UUID other = UUID.randomUUID();
        when(departments.existsByIdAndDeletedAtIsNull(other)).thenReturn(true);
        when(member.getRoles()).thenReturn(List.of(Role.BIZ_ADMIN));
        authorization.check(token(List.of("BIZ_ADMIN")), target(other));
    }

    @Test
    void 없는_부서는_거부한다() {
        denied(token(List.of()), target(UUID.randomUUID()), ErrorCode.NOT_FOUND);
    }

    @Test
    void 토큰_발급_뒤_역할이_바뀌면_연결을_거부한다() {
        when(member.getRoles()).thenReturn(List.of(Role.BIZ_ADMIN));
        denied(token(List.of()), target(departmentId), ErrorCode.AUTH_CONTEXT_CHANGED);
    }

    @Test
    void 차단된_회원은_거부한다() {
        when(members.findWithDepartmentAndRolesByIdAndDeletedAtIsNull(userId)).thenReturn(Optional.empty());
        denied(token(List.of()), target(departmentId), ErrorCode.UNAUTHENTICATED);
    }

    @Test
    void 소유권_연결이_없는_문의는_허용하지_않는다() {
        denied(token(List.of()), new RealtimeTarget(RealtimeTarget.Type.INQUIRY, UUID.randomUUID()),
                ErrorCode.SERVICE_UNAVAILABLE);
    }

    private void denied(Jwt jwt, RealtimeTarget target, ErrorCode code) {
        assertThatThrownBy(() -> authorization.check(jwt, target))
                .isInstanceOfSatisfying(BusinessException.class, error ->
                        org.assertj.core.api.Assertions.assertThat(error.errorCode()).isEqualTo(code));
    }

    private Jwt token(List<String> roles) {
        return Jwt.withTokenValue("test-token").header("alg", "HS256").subject(userId.toString())
                .claim("departmentId", departmentId.toString()).claim("roles", roles)
                .expiresAt(Instant.now().plusSeconds(60)).build();
    }

    private RealtimeTarget target(UUID id) {
        return new RealtimeTarget(RealtimeTarget.Type.DEPARTMENT_TICKETS, id);
    }
}
