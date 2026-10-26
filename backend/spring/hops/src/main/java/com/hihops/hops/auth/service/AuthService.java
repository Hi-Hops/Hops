package com.hihops.hops.auth.service;

import java.util.UUID;

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

    private final MemberRepository memberRepository;
    private final PasswordEncoder passwordEncoder;

    public Member login(String loginId, String password) {
        Member member = memberRepository.findByLoginIdAndDeletedAtIsNull(loginId)
                .orElseThrow(LoginFailedException::new);
        if (!passwordEncoder.matches(password, member.getPasswordHash())) {
            throw new LoginFailedException();
        }
        return member;
    }

    /**
     * 토큰 주인의 현재 회원 정보. 토큰 내용이 아니라 DB를 다시 읽는다.
     * 토큰은 발급 순간의 사진이라, 그 뒤 역할 회수·부서 변경이 있었을 수 있기 때문이다.
     * 토큰은 유효한데 회원이 없거나 삭제(차단)됐으면, 토큰 만료를 기다리지 않고 바로 401로 응답한다.
     */
    public Member currentMember(UUID memberId) {
        return memberRepository.findWithDepartmentAndRolesByIdAndDeletedAtIsNull(memberId)
                .orElseThrow(() -> new BusinessException(ErrorCode.UNAUTHENTICATED));
    }
}
