package com.hihops.hops.global.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import tools.jackson.databind.json.JsonMapper;

/**
 * API 응답의 시각이 docs/API.md §1의 "UTC RFC 3339 문자열"로 나가는지 확인한다.
 * JsonMapper는 Spring이 Controller 응답을 JSON으로 바꿀 때 쓰는 것과 같은 객체다.
 */
@SpringBootTest
@ActiveProfiles({"db", "test"})
class UtcTimeConfigTest {

    @Autowired
    private JsonMapper jsonMapper;

    record TimeResponse(Instant instant, OffsetDateTime offsetDateTime) {
    }

    @Test
    void 한국_시간으로_만든_시각도_UTC_문자열로_응답한다() {
        OffsetDateTime seoulSixPm = OffsetDateTime.of(2026, 10, 23, 18, 0, 0, 0, ZoneOffset.ofHours(9));

        String json = jsonMapper.writeValueAsString(new TimeResponse(seoulSixPm.toInstant(), seoulSixPm));

        assertThat(json).contains("\"instant\":\"2026-10-23T09:00:00Z\"");
        assertThat(json).contains("\"offsetDateTime\":\"2026-10-23T09:00:00Z\"");
    }
}
