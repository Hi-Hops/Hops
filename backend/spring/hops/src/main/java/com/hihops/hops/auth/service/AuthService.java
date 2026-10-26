package com.hihops.hops.auth.service;

import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import com.hihops.hops.user.domain.Member;
import com.hihops.hops.user.repository.MemberRepository;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class AuthService {

    private final MemberRepository memberRepository;
    private final PasswordEncoder passwordEncoder;

    public Member login(String loginId, String password) {
        Member member = memberRepository.findByLoginId(loginId)
                .orElseThrow(LoginFailedException::new);
        if (!passwordEncoder.matches(password, member.getPasswordHash())) {
            throw new LoginFailedException();
        }
        return member;
    }
}
