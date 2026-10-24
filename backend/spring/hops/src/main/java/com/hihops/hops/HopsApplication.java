package com.hihops.hops;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.security.autoconfigure.UserDetailsServiceAutoConfiguration;

// UserDetailsServiceAutoConfiguration 제외: 로그인 방식이 없을 때 Spring이 임시 계정을 만들고
// 비밀번호를 로그에 출력하는 기능이다. Hops는 JWT로 인증하고, 비밀번호를 로그에 남기지 않는다(docs/API.md §1).
@SpringBootApplication(exclude = UserDetailsServiceAutoConfiguration.class)
public class HopsApplication {

    public static void main(String[] args) {
        SpringApplication.run(HopsApplication.class, args);
    }

}
