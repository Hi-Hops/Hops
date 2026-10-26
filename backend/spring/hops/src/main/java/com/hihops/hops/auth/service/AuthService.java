package com.hihops.hops.auth.service;

import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import com.hihops.hops.global.exception.BusinessException;
import com.hihops.hops.global.exception.ErrorCode;
import com.hihops.hops.user.domain.Member;
import com.hihops.hops.user.repository.MemberRepository;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);

    private final MemberRepository memberRepository;
    private final PasswordEncoder passwordEncoder;

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
}
