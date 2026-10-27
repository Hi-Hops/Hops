---
policy_id: POLICY-CASE-LIFECYCLE
version_no: 1
status: DRAFT
access_scope: STAFF
exposure_policy: INTERNAL_ONLY
ingest_status: PENDING
content_review_status: OWNER_CONFIRMED
team_review_status: PENDING
source_content_sha256: 0f9780a771427e0d316f624fef024b11b7e6b4135ada87fdb3818840a30aa695
---

# 해결 확인·재오픈·CASE 생성과 재사용 규칙

## 목적

안내·담당자 조치·도구 성공과 실제 해결을 구분하고, 검증된 해결 기록만 CASE로 승격해 조건부 근거로 재사용한다.

## 규칙

### 10 · RULE-CASE-CONFIRM

- 조건: 직원 안내 또는 담당자 조치가 완료됨
- Agent 동작: 해결을 단정하지 않고 사용자 확인 또는 백엔드 상태 근거를 요청·요약한다.
- 최종 권한: SPRING
- 강제 지점: Spring은 티켓 상태·사용자 응답·백엔드 상태를 검증해 완료 여부를 저장한다.
- 필수 근거:
- 사용자 해결 확인 또는 신뢰 가능한 백엔드 상태
- 대상 문제·티켓 식별자
- 사용자에게 보이는 동작: 실제로 해결됐는지 확인하고 미해결이면 처리를 이어간다.
- 금지 결과:
- 가이드 제공만으로 완료
- Tool 성공만으로 완료
- 여러 티켓의 일괄 완료

### 20 · RULE-CASE-PROMOTE

- 조건: 재현·담당자 조치·동일 조건 재시험·해결 확인이 모두 완료됨
- Agent 동작: 증상·관측·원인·반대 신호·조치·재시험·계보를 구조화한 CASE 후보를 제안한다.
- 최종 권한: HUMAN
- 강제 지점: 데이터 담당자와 서비스 담당자가 비식별·검증 체크리스트를 확인한 뒤 VERIFIED로 승격한다.
- 필수 근거:
- 재현 시각·요청 ID
- 마스킹된 관측값
- 원인과 반대 신호
- 사람 조치와 승인
- 동일 조건 재시험
- 해결 확인
- 런북·Tool 버전
- 사용자에게 보이는 동작: CASE 생성 여부는 직원 답변에 노출하지 않는다.
- 금지 결과:
- 추정 원인의 CASE 승격
- 공개 문서의 티켓 형식 변환
- 트리거 동작만 확인한 기록의 승격

### 30 · RULE-CASE-RETRIEVE

- 조건: 현재 문의와 유사한 VERIFIED CASE가 검색됨
- Agent 동작: 현재 Tool 관측과 CASE 필수·반대 신호를 비교하고 일치할 때만 후보 우선순위 보조 근거로 사용한다.
- 최종 권한: SPRING
- 강제 지점: Spring은 CASE 접근·활성 상태와 현재 관측 스냅샷 버전을 검증한다.
- 필수 근거:
- VERIFIED·활성 CASE
- 현재 Tool 관측
- 연결 런북
- 사용자에게 보이는 동작: 유사 사례가 있더라도 현재 근거에 맞는 안내와 이관 이유만 제공한다.
- 금지 결과:
- 유사도만으로 원인 확정
- CASE와 충돌하는 현재 관측 무시
- 다른 사용자의 식별 정보 노출

### 40 · RULE-CASE-REOPEN

- 조건: 완료된 문제가 해결되지 않았다고 재문의하거나 CASE 해결 검증이 취소됨
- Agent 동작: 기존 문제·티켓·CASE 연결을 유지한 재오픈 또는 검증 취소를 제안한다.
- 최종 권한: SPRING
- 강제 지점: Spring은 대상 티켓만 재오픈하고 관련 CASE를 검색 비활성화하며 이력을 보존한다.
- 필수 근거:
- 미해결 재문의
- 기존 티켓·CASE 식별자
- 사용자에게 보이는 동작: 기존 처리 이력을 유지한 채 다시 점검한다고 안내한다.
- 금지 결과:
- 기존 이력 삭제
- 그룹 전체 재오픈
- 검증 취소 CASE의 계속 검색

## 공통 금지 사항

- 실제 해결되지 않은 합성 CASE 생성
- 원시 SQL·내부 IP·개인정보·비밀값 보존
- 단일 CASE를 보편 정답으로 사용
- pg_sleep 평가 트리거를 해결 CASE로 승격

## 연결 대상

- 직원 가이드: GUIDE-COMMON-PROCESS
- IT 런북: 없음
- 평가 시나리오: FLOW-ANSWER-SOLVED, FLOW-CASE-MATCH, FLOW-CASE-CONFLICT

## 설계 근거

- PRD-3.4
- KNOWLEDGE-LIFECYCLE-6
- KNOWLEDGE-LIFECYCLE-7
- KNOWLEDGE-LIFECYCLE-8
