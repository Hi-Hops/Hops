package com.hihops.hops.inquiry.service;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;

/**
 * 문의 표시 번호를 발급한다 (V22 inquiry_number_seq). 예: Q-00000001.
 *
 * <p>표시 번호는 화면에 보여 주는 값이라 라우팅·멱등·권한에는 쓰지 않는다 (DATA_TYPES 신규 절). 최대 20자를 넘지 않는다.
 */
@Component
@RequiredArgsConstructor
class InquiryNumberGenerator {

    private final JdbcTemplate jdbcTemplate;

    String next() {
        Long value = jdbcTemplate.queryForObject("SELECT nextval('inquiry_number_seq')", Long.class);
        return "Q-%08d".formatted(value);
    }
}
