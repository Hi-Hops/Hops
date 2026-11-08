package com.hihops.hops.inquiry.attachment;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;

import com.hihops.hops.global.exception.BusinessException;
import com.hihops.hops.global.exception.ErrorCode;

class StaffImageContentServiceTest {
    @Test
    void 목록_조회_중_공개_차수가_바뀌면_오래된_결과를_반환하지_않는다() {
        var access = mock(StaffImageAccess.class);
        var service = new StaffImageContentService(access, mock(S3AttachmentStore.class));
        var jwt = mock(Jwt.class);
        UUID ticket = UUID.randomUUID();
        UUID image = UUID.randomUUID();
        when(access.list(jwt, ticket)).thenReturn(List.of(new StaffImageAccess.LinkedRef(image, 4, 1, 1)),
                List.of(new StaffImageAccess.LinkedRef(image, 4, 2, 1)));
        assertThatThrownBy(() -> service.list(jwt, ticket)).isInstanceOfSatisfying(BusinessException.class,
                error -> org.assertj.core.api.Assertions.assertThat(error.errorCode()).isEqualTo(ErrorCode.SERVICE_UNAVAILABLE));
    }
}
