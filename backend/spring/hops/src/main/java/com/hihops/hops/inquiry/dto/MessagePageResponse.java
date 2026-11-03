package com.hihops.hops.inquiry.dto;

import java.util.List;

import org.jspecify.annotations.Nullable;

/**
 * 대화 메시지 한 페이지 (specs/contracts/DATA_TYPES.md 신규 절 MessagePage).
 *
 * <p>items는 오래된 것부터 순서대로다. 더 이전 메시지가 있으면 nextBeforeSequenceNo에 이 페이지의 첫 순번을 담아,
 * 프론트가 그 값을 beforeSequenceNo로 보내 이전 페이지를 받는다. 더 없으면 null이다.
 */
public record MessagePageResponse(List<ChatMessageResponse> items, @Nullable Integer nextBeforeSequenceNo) {
}
