package com.hihops.hops.auth.service;

import java.time.Instant;
import java.util.HashSet;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;

import com.hihops.hops.global.exception.BusinessException;
import com.hihops.hops.global.exception.ErrorCode;
import com.hihops.hops.itsystem.repository.ItSystemRepository;
import com.hihops.hops.user.domain.Member;
import com.hihops.hops.user.dto.CurrentUserResponse;
import com.hihops.hops.user.repository.MemberRepository;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);

    private final MemberRepository memberRepository;
    private final PasswordEncoder passwordEncoder;
    private final ItSystemRepository itSystemRepository;

    /**
     * 로그인 ID·비밀번호를 확인한다. 응답은 실패 이유와 상관없이 같은 401이지만, 로그에는 이유를 남긴다.
     *
     * <p>로그에 남기지 않는 것: 비밀번호, 찾지 못한 로그인 ID 원문(아이디 칸에 비밀번호를 잘못 넣는 경우가 있어서).
     * 모든 로그 줄에는 requestId가 붙으므로, 프론트가 받은 오류의 requestId로 이 줄을 찾을 수 있다.
     */
    public Member login(String loginId, String password) {
        Member member = memberRepository.findByLoginIdAndDeletedAtIsNull(loginId).orElse(null);
        if (member == null) {
            log.warn("로그인 실패: 등록되지 않았거나 차단된 계정");
            throw new LoginFailedException();
        }
        if (!passwordEncoder.matches(password, member.getPasswordHash())) {
            log.warn("로그인 실패: 비밀번호 불일치 memberId={}", member.getId());
            throw new LoginFailedException();
        }
        log.info("로그인 성공 memberId={}", member.getId());
        return member;
    }

    /**
     * 토큰 주인의 현재 회원 정보. 토큰 내용이 아니라 DB를 다시 읽는다.
     * 토큰은 발급 순간의 사진이라, 그 뒤 역할 회수·부서 변경이 있었을 수 있기 때문이다.
     * 토큰은 유효한데 회원이 없거나 삭제(차단)됐으면, 토큰 만료를 기다리지 않고 바로 401로 응답한다.
     */
    public Member currentMember(UUID memberId) {
        return memberRepository.findWithDepartmentAndRolesByIdAndDeletedAtIsNull(memberId)
                .orElseThrow(() -> {
                    log.warn("내 정보 조회 실패: 토큰은 유효하지만 회원이 없거나 차단됨 memberId={}", memberId);
                    return new BusinessException(ErrorCode.UNAUTHENTICATED);
                });
    }

    /** 업무 조회 시 현재 회원·역할·소속을 확인한다. JWT의 역할 스냅샷으로 업무 권한을 부여하지 않는다. */
    public Member currentMember(Jwt jwt) {
        if (jwt == null || jwt.getExpiresAt() == null) throw new BusinessException(ErrorCode.UNAUTHENTICATED);
        if (!Instant.now().isBefore(jwt.getExpiresAt())) throw new BusinessException(ErrorCode.ACCESS_TOKEN_EXPIRED);
        UUID memberId;
        try {
            memberId = UUID.fromString(jwt.getSubject());
        } catch (IllegalArgumentException | NullPointerException exception) {
            throw new BusinessException(ErrorCode.UNAUTHENTICATED);
        }
        Member member = currentMember(memberId);
        Object policy = jwt.getClaim("policyRevision");
        var ownership = jwt.getClaimAsStringList("primarySystemIds");
        if (!(policy instanceof Number revision) || revision.longValue() != member.getPolicyRevision()
                || ownership == null || !new HashSet<>(ownership).equals(new HashSet<>(
                        itSystemRepository.findActiveIdsByPrimaryOwnerId(memberId).stream().map(Object::toString).toList()))) {
            throw new BusinessException(ErrorCode.AUTH_CONTEXT_CHANGED);
        }
        if (jwt.getClaimAsString("departmentId") == null || jwt.getClaimAsStringList("roles") == null) {
            throw new BusinessException(ErrorCode.UNAUTHENTICATED);
        }
        if (member.getDepartment().isDeleted()
                || !member.getDepartment().getId().toString().equals(jwt.getClaimAsString("departmentId"))
                || !new HashSet<>(member.getContractRoles().stream().map(Enum::name).toList())
                        .equals(new HashSet<>(jwt.getClaimAsStringList("roles")))) {
            throw new BusinessException(ErrorCode.AUTH_CONTEXT_CHANGED);
        }
        return member;
    }

    /**
     * 로그인·갱신·내 정보 응답에 담을 현재 사용자 (DATA_TYPES 신규 절 CurrentUser).
     * 담당 시스템(primarySystemIds)도 토큰이 아니라 DB에서 읽는다. 담당자가 바뀌었을 수 있기 때문이다.
     */
    public CurrentUserResponse currentUser(Member member) {
        return CurrentUserResponse.of(member, itSystemRepository.findActiveIdsByPrimaryOwnerId(member.getId()));
    }
}
