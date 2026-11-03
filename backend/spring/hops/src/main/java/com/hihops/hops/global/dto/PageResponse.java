package com.hihops.hops.global.dto;

import java.util.List;
import java.util.function.Function;

import org.springframework.data.domain.Page;

/**
 * 목록 응답의 공통 형식 (specs/contracts/DATA_TYPES.md 신규 절 Page&lt;T&gt;: items·page·size·totalElements).
 *
 * <p>page는 0부터, size는 요청한 크기다. 마지막 페이지를 넘겨도 오류 없이 items=[]다.
 * 문의·티켓·사례 목록이 함께 쓴다. Spring의 Page를 그대로 내보내면 계약에 없는 필드가 섞여 이 형식으로 바꾼다.
 */
public record PageResponse<T>(List<T> items, int page, int size, long totalElements) {

    public static <E, T> PageResponse<T> of(Page<E> page, Function<? super E, ? extends T> mapper) {
        return new PageResponse<>(page.getContent().stream().<T>map(mapper).toList(),
                page.getNumber(), page.getSize(), page.getTotalElements());
    }
}
