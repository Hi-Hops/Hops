---
guide_id: GUIDE-FILE-ACCESS
version_no: 1
status: DRAFT
service_id: FILE_SHARE
access_scope: EMPLOYEE
exposure_policy: QUOTABLE
ingest_status: PENDING
content_review_status: OWNER_CONFIRMED
team_review_status: PENDING
source_content_sha256: 867428ad2109159c88431942b203c02d5ca20b7b56398688ce5db35e7e83068b
---

# 공유 폴더 액세스가 거부될 때

## 적용 상황

- 회사 공유 폴더에서 Access Denied 또는 권한 없음 오류가 표시됨
- Windows 보안 정책이 인증되지 않은 게스트 접근을 차단했다는 문구가 표시됨

## 사용자 표현

- 공유 폴더에 액세스할 권한이 없다고 나와요.
- 어제까지 열리던 폴더가 거부돼요.
- 회사 보안 정책 때문에 공유 폴더를 열 수 없다고 해요.

## 안전한 확인 순서

1. 사내망 또는 승인된 VPN에 연결되어 있는지 확인합니다.
   - 예상 결과: 회사 파일 서버에 접근할 네트워크 조건을 확인합니다.
2. IP 주소 대신 회사에서 안내한 서버 이름과 공유 경로를 사용했는지 확인합니다.
   - 예상 결과: 회사 인증이 가능한 경로로 접속했는지 확인합니다.
3. 본인에게 원래 허용된 다른 공유 폴더가 열리는지 한 번만 확인합니다.
   - 예상 결과: 특정 폴더 권한 문제인지 공유 서비스 전체 문제인지 구분할 수 있습니다.
4. 오류가 계속되면 권한 변경을 시도하지 말고 오류 문구와 공유 이름을 기록해 Hops에 문의합니다.
   - 예상 결과: 시스템 담당자가 인증 정책과 최소 권한을 확인합니다.

## 중단 및 이관 조건

- Access Denied 또는 System error 5가 반복됨
- 보안 정책 또는 게스트 접근 차단 문구가 표시됨
- 다른 사용자는 되지만 본인만 접근할 수 없음
- 원래 허용된 여러 공유 폴더가 함께 거부됨

## 문의에 준비할 정보

- 안전한 오류 문구와 코드
- 발생 시각
- 마스킹한 서버·공유 이름
- 다른 허용 공유 폴더의 결과
- 최근 Windows 업데이트 또는 장치 변경 여부

## 하지 말아야 할 일

- Everyone 전체 제어를 부여하지 않기
- SMB1 또는 insecure guest logon을 활성화하지 않기
- 레지스트리·그룹 정책·NTFS 권한을 직접 변경하지 않기
- 비밀번호나 다른 사용자의 권한 정보를 공유하지 않기

## 계보

- 관련 런북: RB-FILE-SMB-ACCESS
- 공개 근거: GE-022, GE-060
- 프로젝트 정책: 없음
