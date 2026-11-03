package com.hihops.hops.global.idempotency;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.oauth2.jwt.Jwt;

import com.hihops.hops.global.exception.BusinessException;
import com.hihops.hops.global.exception.ErrorCode;

/** Idempotency-Key 헤더와 본문 requestId가 같은 값인지 검사하는 규칙 (DATA_TYPES 신규 절 "requestId는 Idempotency-Key와 같아야 한다"). */
class IdempotentRequestTest {

    private static final UUID MEMBER_ID = UUID.randomUUID();
    private static final UUID REQUEST_ID = UUID.fromString("0f8fad5b-d9cb-469f-a165-70867728950e");

    @Test
    void 헤더와_본문의_요청_키가_같으면_요청자와_정규화한_경로로_요청을_만든다() {
        MockHttpServletRequest http = new MockHttpServletRequest("POST",
                "/api/v2/Inquiries/E0000000-0000-7000-8000-000000000001/messages/");

        IdempotentRequest request = IdempotentRequest.of(jwt(), http, REQUEST_ID.toString().toUpperCase(), REQUEST_ID,
                "body");

        assertThat(request.actorId()).isEqualTo(MEMBER_ID);
        assertThat(request.requestId()).isEqualTo(REQUEST_ID);
        assertThat(request.httpMethod()).isEqualTo("POST");
        assertThat(request.requestPath()).isEqualTo("/api/v2/inquiries/e0000000-0000-7000-8000-000000000001/messages");
    }

    @Test
    void 헤더가_없거나_UUID가_아니거나_본문과_다르면_400이다() {
        MockHttpServletRequest http = new MockHttpServletRequest("POST", "/api/v2/inquiries");

        for (String header : new String[] {null, "", "not-a-uuid", UUID.randomUUID().toString()}) {
            assertThatThrownBy(() -> IdempotentRequest.of(jwt(), http, header, REQUEST_ID, "body"))
                    .isInstanceOfSatisfying(BusinessException.class,
                            e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.INVALID_INPUT));
        }
        assertThatThrownBy(() -> IdempotentRequest.of(jwt(), http, REQUEST_ID.toString(), null, "body"))
                .isInstanceOf(BusinessException.class);
    }

    private static Jwt jwt() {
        return Jwt.withTokenValue("token").header("alg", "none").subject(MEMBER_ID.toString())
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(60)).build();
    }
}
