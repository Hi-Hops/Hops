---
guide_id: GUIDE-REMOTE-VPN
version_no: 1
status: DRAFT
service_id: REMOTE_ACCESS
access_scope: EMPLOYEE
exposure_policy: QUOTABLE
ingest_status: PENDING
content_review_status: OWNER_CONFIRMED
team_review_status: PENDING
source_content_sha256: 355ab0fe268678b795357aa402017c4cd9177d34e01502408fcc79aa85b2c8bd
---

# 회사 VPN이 연결되지 않을 때

## 적용 상황

- 승인된 회사 VPN 프로필로 연결할 때 오류 코드가 표시됨
- 회사 VPN이 연결 중 멈추거나 연결되지 않음

## 사용자 표현

- VPN이 연결되지 않아요.
- VPN 오류 809가 떠요.
- VPN 오류 720이 표시돼요.

## 안전한 확인 순서

1. 오류 코드, 발생 시각, 사용한 회사 VPN 프로필 이름을 기록합니다.
   - 예상 결과: 담당자가 같은 시간대의 VPN 기록을 찾을 수 있습니다.
2. 인터넷 연결이 정상인지 확인하고 VPN 연결을 한 번만 다시 시도합니다.
   - 예상 결과: 일시적인 연결 실패인지 확인할 수 있습니다.
3. 회사에서 허용한 다른 네트워크를 사용할 수 있다면 한 번만 비교합니다.
   - 예상 결과: 현재 네트워크에 한정된 문제인지 구분할 정보가 생깁니다.
4. 같은 오류가 반복되면 설정을 바꾸지 말고 기록한 정보로 Hops에 문의합니다.
   - 예상 결과: 네트워크 담당자가 서버·인증·단말 상태를 확인합니다.

## 중단 및 이관 조건

- 오류 720·809·812 또는 인증서 관련 오류가 반복됨
- 다른 네트워크에서도 같은 오류가 발생함
- VPN 연결 뒤에도 회사 자원에 접근할 수 없음

## 문의에 준비할 정보

- 오류 코드와 안전한 오류 문구
- 발생 시각
- 회사 VPN 프로필 이름
- 인터넷 연결 여부
- 다른 승인 네트워크에서의 결과

## 하지 말아야 할 일

- 방화벽이나 보안 프로그램을 끄지 않기
- VPN 프로필·인증서·네트워크 드라이버를 임의로 변경하거나 삭제하지 않기
- 관리자 PowerShell 명령을 실행하지 않기
- 비밀번호·인증 코드를 문의에 입력하지 않기

## 계보

- 관련 런북: RB-REMOTE-VPN-CODES, RB-REMOTE-VPN-720
- 공개 근거: GE-011, GE-012
- 프로젝트 정책: 없음
