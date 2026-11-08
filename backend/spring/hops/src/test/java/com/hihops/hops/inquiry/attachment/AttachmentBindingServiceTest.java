package com.hihops.hops.inquiry.attachment;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import com.hihops.hops.agent.service.AgentDispatchProperties;
import com.hihops.hops.global.exception.BusinessException;
import com.hihops.hops.global.exception.ErrorCode;

class AttachmentBindingServiceTest {
    @Test
    void AI_전달이_켜져_있으면_실험_설정을_켜도_첨부를_연결하지_않는다() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        var dispatch = new AgentDispatchProperties(true, Duration.ofMillis(500), Duration.ofSeconds(5));
        var service = new AttachmentBindingService(jdbc, dispatch, true);
        assertThatThrownBy(() -> service.bind(UUID.randomUUID(), UUID.randomUUID(), List.of(UUID.randomUUID())))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> org.assertj.core.api.Assertions.assertThat(exception.errorCode())
                                .isEqualTo(ErrorCode.SERVICE_UNAVAILABLE));
        verifyNoInteractions(jdbc);
    }
}
