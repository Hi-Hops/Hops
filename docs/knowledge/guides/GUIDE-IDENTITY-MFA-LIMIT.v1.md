---
guide_id: GUIDE-IDENTITY-MFA-LIMIT
version_no: 1
status: DRAFT
service_id: IDENTITY
access_scope: EMPLOYEE
exposure_policy: QUOTABLE
ingest_status: PENDING
content_review_status: OWNER_CONFIRMED
team_review_status: PENDING
source_content_sha256: c75009a1fa13899d3e33b390482f91ade7e2b2e19e3ea86c7291a19f787106de
---

# MFA 인증 앱을 더 등록할 수 없을 때

## 적용 상황

- Authenticator 앱 또는 인증 장치를 추가하는 중 등록 한도 오류가 표시됨

## 사용자 표현

- 인증 앱을 더 등록할 수 없다고 나와요.
- 등록된 기기가 너무 많다는 오류가 보여요.

## 안전한 확인 순서

1. 오류 문구가 인증 앱 또는 장치 등록 한도와 관련된 것인지 확인합니다.
   - 예상 결과: 다른 MFA 장애와 등록 한도 문제를 구분합니다.
2. 회사에서 안내한 보안 정보 화면에서 본인이 더 이상 사용하지 않는 인증 수단이 보이는지만 확인합니다.
   - 예상 결과: 정리할 수단이 있는지 확인하되 바로 삭제하지 않습니다.
3. 사용 가능한 인증 수단이 하나뿐이거나 어떤 수단을 지워야 할지 불확실하면 Hops에 문의합니다.
   - 예상 결과: 계정 권한 담당자가 재등록과 안전한 정리 순서를 안내합니다.

## 중단 및 이관 조건

- 현재 사용할 수 있는 인증 수단이 하나뿐임
- 보안 정보 화면에 접근할 수 없음
- 하드웨어 토큰 또는 회사 관리 장치가 포함됨

## 문의에 준비할 정보

- 안전한 오류 문구
- 발생 시각
- 등록된 수단의 종류와 개수
- 현재 사용할 수 있는 인증 수단 유무

## 하지 말아야 할 일

- 사용 가능한 마지막 인증 수단을 삭제하지 않기
- 인증 수단 내부 ID나 인증 코드를 공유하지 않기
- Graph 또는 관리자 삭제 절차를 직접 실행하지 않기

## 계보

- 관련 런북: RB-IDENTITY-MFA-RECOVERY
- 공개 근거: GE-001
- 프로젝트 정책: 없음
