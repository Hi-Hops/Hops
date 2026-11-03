package com.hihops.hops.global.idempotency;

import java.util.UUID;

/**
 * 변경 요청으로 저장한 업무 대상의 참조 (DATA_TYPES 신규 절 ResourceRef).
 *
 * <pre>
 * { "type": "INQUIRY", "id": "UUID" }
 * </pre>
 *
 * @param type 대상 종류
 * @param id   대상 ID. 현재 권한으로 GET할 수 있는 대상이다
 */
public record ResourceRef(ResourceType type, UUID id) {
}
