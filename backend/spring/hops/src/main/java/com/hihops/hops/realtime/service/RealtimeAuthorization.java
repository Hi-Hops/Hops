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
import com.hihops.hops.inquiry.repository.InquiryRepository;
import com.hihops.hops.knowledge.service.CaseQueryService;
import com.hihops.hops.realtime.dto.RealtimeTarget;
import com.hihops.hops.ticket.service.TicketService;
import com.hihops.hops.user.domain.Member;
import com.hihops.hops.user.domain.Role;
import com.hihops.hops.user.repository.MemberRepository;

@Service
public class RealtimeAuthorization {
    private final MemberRepository members;
    private final DepartmentRepository departments;
    private final TicketService tickets;
    private final InquiryRepository inquiries;
    private final CaseQueryService cases;

    public RealtimeAuthorization(MemberRepository members, DepartmentRepository departments, TicketService tickets,
            InquiryRepository inquiries, CaseQueryService cases) {
        this.members = members;
        this.departments = departments;
        this.tickets = tickets;
        this.inquiries = inquiries;
        this.cases = cases;
    }

    @Transactional(readOnly = true)
    public void check(Jwt jwt, RealtimeTarget target) {
        if (target.type() == RealtimeTarget.Type.TICKET) {
            tickets.requireStaffTicket(jwt, target.id());
            return;
        }
        if (target.type() == RealtimeTarget.Type.SYSTEM_TICKETS) {
            tickets.checkSystemSubscription(jwt, target.id());
            return;
        }
        // 사례는 상세 조회와 같이 현재 사례 시스템의 주담당자만 구독한다 (WIRE_CONTRACT 12.4).
        if (target.type() == RealtimeTarget.Type.CASE) {
            cases.requireReviewer(jwt, target.id());
            return;
        }
        if (jwt.getExpiresAt() == null) {
            throw new BusinessException(ErrorCode.UNAUTHENTICATED);
        }
        // 디코더는 시계 오차(30초) 안의 만료 토큰도 통과시킨다. 만료는 만료로 알려야 프론트가 refresh 후 다시 연결한다.
        if (!Instant.now().isBefore(jwt.getExpiresAt())) {
            throw new BusinessException(ErrorCode.ACCESS_TOKEN_EXPIRED);
        }
        UUID memberId;
        try {
            memberId = UUID.fromString(jwt.getSubject());
        } catch (IllegalArgumentException | NullPointerException exception) {
            throw new BusinessException(ErrorCode.UNAUTHENTICATED);
        }
        Member member = members.findWithDepartmentAndRolesByIdAndDeletedAtIsNull(memberId)
                .orElseThrow(() -> new BusinessException(ErrorCode.UNAUTHENTICATED));
        if (jwt.getClaimAsString("departmentId") == null || jwt.getClaimAsStringList("roles") == null) {
            throw new BusinessException(ErrorCode.UNAUTHENTICATED);
        }
        // 유효한 JWT라도 발급 이후 소속·역할이 바뀌면 갱신 반복 대신 재로그인이 필요하다.
        if (member.getDepartment().isDeleted()
                || !member.getDepartment().getId().toString().equals(jwt.getClaimAsString("departmentId"))
                // 토큰 발급과 같은 기준(계약 역할)으로 비교한다 (Role.contractRoles)
                || !new HashSet<>(Role.contractRoles(member.getRoles()).stream().map(Role::name).toList())
                        .equals(new HashSet<>(jwt.getClaimAsStringList("roles")))) {
            throw new BusinessException(ErrorCode.AUTH_CONTEXT_CHANGED);
        }
        // 문의는 문의자 본인만 구독한다 (WIRE_CONTRACT 12.4). 남의 문의·삭제된 문의·없는 문의는 REST와 같은 404다.
        if (target.type() == RealtimeTarget.Type.INQUIRY) {
            if (inquiries.findByIdAndRequester_IdAndDeletedAtIsNull(target.id(), memberId).isEmpty()) {
                throw new BusinessException(ErrorCode.NOT_FOUND);
            }
            return;
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
