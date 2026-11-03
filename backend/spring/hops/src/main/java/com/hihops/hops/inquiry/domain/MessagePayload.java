package com.hihops.hops.inquiry.domain;

import java.util.List;
import java.util.UUID;

import org.jspecify.annotations.Nullable;

/**
 * message.payload(JSONB)에서 대화 조회에 쓰는 값 (docs/DATA.md 11.3 message.payload).
 *
 * <p>payload의 다른 키(answerRunId·questionBundles·guide·pendingTarget 등)는 여기서 읽지 않는다. 필요한 기능이 생길 때 추가한다.
 * 없는 키는 null로 읽히므로 접근 메서드에서 빈 값으로 바꾼다.
 *
 * @param requestId 사용자 메시지를 처음 만든 변경 요청의 ID. 정정해도 바뀌지 않는다. AI 메시지는 null
 * @param problemLinks 서버가 확인한 이 메시지와 문제의 연결
 */
public record MessagePayload(@Nullable UUID requestId, @Nullable List<ProblemLink> problemLinks) {

    /** 메시지가 이어진 문제. 원문 구간(spans)·차수는 담당자 화면에서 쓰므로 여기서는 문제 ID만 읽는다. */
    public record ProblemLink(UUID problemId) {
    }

    /** 연결된 문제 ID. 같은 문제가 구간별로 여러 번 연결돼도 한 번만, 처음 나온 순서대로. */
    public List<UUID> problemIds() {
        if (problemLinks == null) {
            return List.of();
        }
        return problemLinks.stream().map(ProblemLink::problemId).distinct().toList();
    }
}
