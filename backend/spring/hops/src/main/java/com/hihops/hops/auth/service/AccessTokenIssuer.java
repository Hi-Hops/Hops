package com.hihops.hops.auth.service;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Component;

import com.hihops.hops.department.domain.Department;
import com.hihops.hops.global.security.JwtConfig;
import com.hihops.hops.global.security.JwtProperties;
import com.hihops.hops.user.domain.Member;
import com.hihops.hops.user.domain.Role;

import lombok.RequiredArgsConstructor;

/**
 * 로그인한 회원에게 액세스 토큰(JWT)을 발급한다. 클레임은 docs/API.md §2.5를 따른다.
 *
 * <table>
 *   <tr><th>클레임</th><th>값</th></tr>
 *   <tr><td>sub</td><td>회원 UUID (사번·로그인 ID 아님)</td></tr>
 *   <tr><td>employeeNo</td><td>사번</td></tr>
 *   <tr><td>departmentId, departmentCode, departmentName</td><td>사용자 소속 부서. 이름은 표시용이고 권한은 ID로 검사한다</td></tr>
 *   <tr><td>roles</td><td>역할 이름 목록 (예: ["BIZ_ADMIN"]). 없으면 빈 배열</td></tr>
 *   <tr><td>iss, aud</td><td>hops.jwt.issuer, hops.jwt.audience — 검증 쪽(JwtConfig)과 같은 값</td></tr>
 *   <tr><td>iat, exp</td><td>발급 시각, 발급 후 15분</td></tr>
 * </table>
 *
 * <p>토큰 내용은 누구나 열어 읽을 수 있으므로(서명은 위조 방지일 뿐 암호화가 아님) 비밀번호 등은 넣지 않는다.
 */
@Component
@RequiredArgsConstructor
public class AccessTokenIssuer {

    /** 계약(WIRE_CONTRACT 10절): Access Token 유효기간 15분. */
    static final Duration LIFETIME = Duration.ofMinutes(15);

    private final JwtEncoder jwtEncoder;
    private final JwtProperties jwtProperties;

    public IssuedToken issue(Member member) {
        // JWT 시각은 초 단위로 저장되므로, 응답의 expiresAt과 토큰의 exp가 똑같도록 미리 초 단위로 자른다.
        Instant issuedAt = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        Instant expiresAt = issuedAt.plus(LIFETIME);

        Department department = member.getDepartment();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .subject(member.getId().toString())
                .claim("employeeNo", member.getEmployeeNo())
                .claim("departmentId", department.getId().toString())
                .claim("departmentCode", department.getCode())
                .claim("departmentName", department.getName())
                .claim("roles", member.getRoles().stream().map(Role::name).toList())
                .issuer(jwtProperties.issuer())
                .audience(List.of(jwtProperties.audience()))
                .issuedAt(issuedAt)
                .expiresAt(expiresAt)
                .build();
        JwsHeader header = JwsHeader.with(JwtConfig.ALGORITHM).build();

        String value = jwtEncoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
        return new IssuedToken(value, expiresAt);
    }

    /** 발급한 토큰 문자열과 만료 시각. */
    public record IssuedToken(String value, Instant expiresAt) {

        // 토큰 원문이 로그에 남지 않도록 만료 시각만 보여준다.
        @Override
        public String toString() {
            return "IssuedToken[expiresAt=" + expiresAt + "]";
        }
    }
}
