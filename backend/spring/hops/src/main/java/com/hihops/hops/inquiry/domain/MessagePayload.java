package com.hihops.hops.inquiry.domain;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.jspecify.annotations.Nullable;

/**
 * message.payload(JSONB)에서 대화 조회·실행 입력에 쓰는 값 (docs/DATA.md 11.3 message.payload).
 *
 * <p>payload의 다른 키(answerRunId·questionBundles·guide·pendingTarget 등)는 여기서 읽지 않는다. 필요한 기능이 생길 때 추가한다.
 * 없는 키는 null로 읽히므로 접근 메서드에서 빈 값으로 바꾼다.
 *
 * @param requestId          사용자 메시지를 처음 만든 변경 요청의 ID. 정정해도 바뀌지 않는다. AI 메시지는 null
 * @param problemLinks       서버가 확인한 이 메시지와 문제의 연결. 원문 차수(revision)마다 따로 둔다
 * @param revisionRequestIds 정정한 차수 → 그 정정 요청의 ID. 정정으로 시작한 실행의 오류에 접수 명령 ID를 넣는 데 쓴다
 * @param pendingTarget      보완 대상을 아직 정하지 못한 메시지인지 (없으면 false)
 * @param runRequestIds      이 메시지를 시작점으로 접수한 실행 ID → 그 실행을 접수한 공개 명령 ID(전송·정정·보완 대상 확정)
 */
public record MessagePayload(@Nullable UUID requestId, @Nullable List<ProblemLink> problemLinks,
        @Nullable Map<String, UUID> revisionRequestIds, @Nullable Boolean pendingTarget,
        @Nullable Map<String, UUID> runRequestIds) {

    public boolean isPendingTarget() {
        return Boolean.TRUE.equals(pendingTarget);
    }

    /**
     * 메시지가 이어진 문제. 원문 구간(spans)은 담당자 화면에서 쓰므로 여기서는 문제 ID와 차수만 읽는다.
     *
     * @param revision 이 연결이 가리키는 원문 차수. 없으면 차수와 관계없이 연결된 것으로 본다
     */
    public record ProblemLink(UUID problemId, @Nullable Integer revision) {
    }

    /** 현재 원문(revision)에 연결된 문제 ID. 같은 문제가 구간별로 여러 번 연결돼도 한 번만, 처음 나온 순서대로. */
    public List<UUID> problemIds(int revision) {
        if (problemLinks == null) {
            return List.of();
        }
        return problemLinks.stream()
                .filter(link -> link.revision() == null || link.revision() == revision)
                .map(ProblemLink::problemId).distinct().toList();
    }

    /** 이 실행을 접수한 공개 명령 ID. 기록 전에 만든 실행이면 null. */
    public @Nullable UUID runRequestId(UUID runId) {
        return runRequestIds == null ? null : runRequestIds.get(runId.toString());
    }

    /** 이 차수를 만든 정정 요청 ID. 처음 원문(1)이거나 기록이 없으면 null. */
    public @Nullable UUID revisionRequestId(int revision) {
        return revisionRequestIds == null ? null : revisionRequestIds.get(Integer.toString(revision));
    }
}
