---
policy_id: POLICY-INQUIRY-FLOW
version_no: 1
status: DRAFT
access_scope: STAFF
exposure_policy: INTERNAL_ONLY
ingest_status: PENDING
content_review_status: OWNER_CONFIRMED
team_review_status: PENDING
source_content_sha256: 7d748ce640d6100c4ab46890229d3b33be8dd0092bd2c41460e760cbb0630b36
---

> **PRD 1.44 전환 전 자료:** 이 문서는 기존 v0 데이터의 자동 생성본이다. 파일명의 `.v1`은 해당 문서의 버전이며 새 기획용 데이터셋 v1을 뜻하지 않는다. 부서 배정·질문 한도·해결 정책 등은 현재 기획과 다를 수 있으므로 새 기획의 검색 정본으로 사용하지 않는다. [새 정책](../policies/POLICY-PRD-1.44.md) · [자료 전환 검토](../DATA_MIGRATION_REVIEW.md)

# 문의 접수·추가 질문·답변에서 진단 전환 규칙

## 목적

근거 있는 직원 안내와 추가 진단·이관의 경계를 정하고, 복합 문의와 범위 밖 문의를 일관되게 처리한다.

## 규칙

### 10 · RULE-INQUIRY-ANSWER

- 조건: EMPLOYEE/QUOTABLE로 승인된 가이드에 현재 문의와 직접 일치하는 안전한 조치가 있음
- Agent 동작: 근거와 중단 조건을 포함해 안내하고 사용자에게 실제 해결 여부를 확인한다.
- 최종 권한: SPRING
- 강제 지점: Spring은 접근 가능한 근거와 문의 버전을 검증하고 답변·해결 확인 상태를 저장한다.
- 필수 근거:
- 승인된 직원 가이드
- 현재 문의 증상과 적용 조건의 일치
- 사용자에게 보이는 동작: 안전한 확인 순서와 미해결 시 다음 처리 방법을 보여 준다.
- 금지 결과:
- 사용자 확인 전 해결 완료
- STAFF 원문 인용
- 근거 없는 해결 절차 생성

### 20 · RULE-INQUIRY-DIAGNOSIS

- 조건: 직원 가이드 근거가 부족하거나 안내 후 사용자가 미해결이라고 응답함
- Agent 동작: 기존 문제 문맥을 유지해 SUPPORT_DIAGNOSIS 또는 담당자 이관을 제안한다.
- 최종 권한: SPRING
- 강제 지점: Spring은 최신 문의 버전과 기존 티켓을 확인하고 전환 결과를 확정한다.
- 필수 근거:
- 검색 근거 부족 또는 미해결 응답
- 기존 문의·문제 식별자
- 사용자에게 보이는 동작: 확인된 범위와 부족한 정보를 설명하고 진단 또는 이관 진행을 알린다.
- 금지 결과:
- 새 문의로 임의 분리
- 해결됐다고 간주
- 원인·부서 임의 확정

### 30 · RULE-INQUIRY-QUESTIONS

- 조건: 문제 분류나 부서 판단에 필요한 정보가 부족함
- Agent 동작: 문제별로 필요한 추가 질문을 하되 최대 3회 안에서 확인 가능한 사실만 요청한다.
- 최종 권한: SPRING
- 강제 지점: Spring은 문제별 질문 횟수와 최신 문의 버전을 저장·검증한다.
- 필수 근거:
- 누락된 필수 정보
- 문제별 질문 횟수
- 사용자에게 보이는 동작: 왜 필요한지 알 수 있는 짧은 질문을 제공한다.
- 금지 결과:
- 3회를 넘는 반복 질문
- 비밀번호·토큰·인증 코드 요구
- 질문 횟수를 문의 전체에 잘못 합산

### 40 · RULE-INQUIRY-MULTI

- 조건: 한 문의에 서로 독립적인 문제가 둘 이상 포함됨
- Agent 동작: 문제를 처리 단위별로 분리하고 각 문제의 근거·상태·후보 부서를 독립적으로 제안한다.
- 최종 권한: SPRING
- 강제 지점: Spring은 문제와 티켓의 중복·멱등성·부분 성공을 검증하고 저장한다.
- 필수 근거:
- 서로 다른 증상·서비스 또는 처리 부서 근거
- 사용자에게 보이는 동작: 문제별 진행 상태와 생성된 티켓을 구분해 보여 준다.
- 금지 결과:
- 문제 하나로 강제 병합
- 고정 티켓 수 상한
- 한 문제 실패로 성공 티켓 취소

### 50 · RULE-INQUIRY-OUT-OF-SCOPE

- 조건: IT 지원 범위 밖 질문으로 확인되거나 3회 질문 후에도 기술 문제로 분류할 수 없음
- Agent 동작: 범위 밖이면 안내만 제공하고, 분류 불가 IT 문의이면 CS 검토를 제안한다.
- 최종 권한: SPRING
- 강제 지점: Spring은 범위 밖과 CS 검토를 구분하고 불필요한 기술 티켓 생성을 차단한다.
- 필수 근거:
- 범위 판단 근거
- 문제별 질문 횟수
- 사용자에게 보이는 동작: 처리할 수 없는 이유 또는 CS 검토 필요성을 설명한다.
- 금지 결과:
- 범위 밖 기술 티켓 생성
- 모델 장애를 CS 분류로 처리
- 임의 부서 배정

## 공통 금지 사항

- Agent의 코드·설정·배포·재시작·DB 쓰기
- 모델 문구만으로 해결·저장 성공 확정
- 평가 정답 또는 숨겨진 원인을 답변 근거로 사용

## 연결 대상

- 직원 가이드: GUIDE-COMMON-INQUIRY-INFO, GUIDE-COMMON-PROCESS, GUIDE-COMMON-SCOPE
- IT 런북: 없음
- 평가 시나리오: FLOW-ANSWER-SOLVED, FLOW-ANSWER-INSUFFICIENT, FLOW-GUIDANCE-NOT-SOLVED, FLOW-NO-EVIDENCE-HANDOFF, FLOW-MULTI-PROBLEM, FLOW-NO-WRITE-ACTION

## 설계 근거

- PRD-3.1
- PRD-3.2
- PRD-4.4
- DATA-INQUIRY
