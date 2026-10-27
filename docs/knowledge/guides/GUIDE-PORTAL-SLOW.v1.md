---
guide_id: GUIDE-PORTAL-SLOW
version_no: 1
status: DRAFT
service_id: PORTAL
access_scope: EMPLOYEE
exposure_policy: QUOTABLE
ingest_status: PENDING
content_review_status: OWNER_CONFIRMED
team_review_status: PENDING
source_content_sha256: 64348d29e75182f1422bda772cd1cf907f9adad1a08e4ad74c63dcd00433fd27
---

# 사내 포털 조회가 느릴 때

## 적용 상황

- 포털 검색·목록·상세 조회가 평소보다 오래 걸림
- 화면이 계속 로딩되지만 즉시 오류가 표시되지는 않음

## 사용자 표현

- 포털 검색이 너무 느려요.
- 목록이나 상세 화면이 계속 로딩 중이에요.
- 사용자가 많을 때 포털이 느려져요.

## 안전한 확인 순서

1. 느려진 메뉴와 대략적인 시작 시각을 기록합니다.
   - 예상 결과: 담당자가 같은 시간대의 서버 상태를 확인할 수 있습니다.
2. 처리 중인 화면에서 새로고침이나 같은 검색을 연속으로 반복하지 않습니다.
   - 예상 결과: 중복 요청으로 지연을 더 키우는 것을 피합니다.
3. 다른 포털 메뉴도 느린지 한 번 확인합니다.
   - 예상 결과: 특정 기능 문제인지 포털 전체 문제인지 구분할 정보가 생깁니다.
4. 지연이 계속되면 메뉴·시각·요청 추적 번호를 포함해 Hops에 문의합니다.
   - 예상 결과: 담당 부서가 반복되는 지연 근거를 조사할 수 있습니다.

## 중단 및 이관 조건

- 조회가 timeout 또는 서버 오류로 끝남
- 여러 사용자가 같은 시간대에 느림을 겪음
- 업무 마감에 영향을 줄 정도로 지연이 지속됨

## 문의에 준비할 정보

- 느린 메뉴
- 발생 시작 시각
- 특정 조건에서만 느린지 여부
- 다른 메뉴 영향 여부
- 요청 추적 번호

## 하지 말아야 할 일

- 같은 검색·조회 버튼을 빠르게 반복하지 않기
- DB나 브라우저 내부 설정을 임의로 변경하지 않기
- SQL·서버 정보를 요구하거나 문의에 입력하지 않기

## 계보

- 관련 런북: RB-PORTAL-HIKARI-POOL, RB-PORTAL-DB-CONNECTION-LIMIT, RB-PORTAL-DB-LOCK, RB-PORTAL-DB-SLOW-QUERY
- 공개 근거: PE-006, PE-009, PE-015, PE-020, PE-023, PE-025
- 프로젝트 정책: 없음
