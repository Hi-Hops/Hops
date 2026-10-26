package com.hihops.hops.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import com.hihops.hops.department.domain.Department;
import com.hihops.hops.user.domain.Member;
import com.hihops.hops.user.repository.MemberRepository;

/**
 * 로그인 판단 규칙만 확인하는 단위 테스트. DB 없이 빠르게 돈다.
 *
 * <p>MemberRepository는 mock(가짜 객체)으로 바꿔 "requester를 찾으면 이 회원을 돌려준다"고 미리 정해 둔다.
 * 실제 DB 조회는 MemberRepositoryTest, API 전체 흐름은 AuthControllerTest에서 확인한다.
 */
class AuthServiceTest {

    private final PasswordEncoder passwordEncoder = new BCryptPasswordEncoder();
    private final MemberRepository memberRepository = mock(MemberRepository.class);
    private final AuthService authService = new AuthService(memberRepository, passwordEncoder);

    private final Member requester = new Member("requester", "20260001",
            passwordEncoder.encode("hops1234!"), "테스트 요청자", new Department("SALES", "영업팀", false));

    @BeforeEach
    void 저장소에는_requester만_있다() {
        when(memberRepository.findByLoginIdAndDeletedAtIsNull(anyString())).thenReturn(Optional.empty());
        when(memberRepository.findByLoginIdAndDeletedAtIsNull("requester")).thenReturn(Optional.of(requester));
    }

    @Test
    void 로그인ID와_비밀번호가_맞으면_로그인한_회원을_반환한다() {
        Member loggedIn = authService.login("requester", "hops1234!");

        assertThat(loggedIn).isSameAs(requester);
        assertThat(loggedIn.getEmployeeNo()).isEqualTo("20260001");
    }

    @Test
    void 비밀번호가_틀리면_로그인에_실패한다() {
        assertThatThrownBy(() -> authService.login("requester", "wrong-password"))
                .isInstanceOf(LoginFailedException.class);
    }

    @Test
    void 없는_로그인ID면_비밀번호_오류와_같은_예외로_실패한다() {
        assertThatThrownBy(() -> authService.login("unknown", "hops1234!"))
                .isInstanceOf(LoginFailedException.class);
    }

    @Test
    void 사번으로는_로그인할_수_없다() {
        assertThatThrownBy(() -> authService.login("20260001", "hops1234!"))
                .isInstanceOf(LoginFailedException.class);
    }
}
