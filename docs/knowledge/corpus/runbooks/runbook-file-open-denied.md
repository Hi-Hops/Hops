---
documentKey: runbook-file-open-denied
kind: RUNBOOK
title: 파일 공유의 파일이나 폴더가 열리지 않을 때
systemCode: FILE_SHARING
accessScope: SYSTEM_STAFF
version: 1
appliesToSystemVersion: null
dataSplit: RAG
origin: SYNTHETIC
reviewStatus: APPROVED
source:
  type: AUTHORED
  reference: null
symptoms:
  - id: sym-file-not-open
    label: 공유 파일이나 폴더가 열리지 않음
    featureCode: FILE_VIEW
    causes:
      - id: cause-file-folder-no-access
        label: 폴더 접근 권한이 없음
        checks: [check-file-error-message, check-file-access-route]
      - id: cause-file-moved-deleted
        label: 파일이 삭제되었거나 이동됨
        checks: [check-file-error-message, check-file-recent-change]
      - id: cause-file-link-expired
        label: 공유 링크의 만료일이 지남
        checks: [check-file-error-message, check-file-link-received]
checks:
  - id: check-file-error-message
    label: 화면에 표시된 문구
    employeeDisclosable: true
  - id: check-file-access-route
    label: 폴더 목록에서 열었는지, 링크로 열었는지
    employeeDisclosable: true
  - id: check-file-recent-change
    label: 그 파일이 최근 삭제되거나 이동됐는지 아는지
    employeeDisclosable: true
  - id: check-file-link-received
    label: 공유 링크를 받은 시점
    employeeDisclosable: true
---

## 증상

문의자가 "파일 공유의 파일이나 폴더가 열리지 않는다"고 말한다. 대상 기능은 파일 열람(`FILE_VIEW`)이며 증상 `sym-file-not-open`이다. 이 문서는 담당자용이며 직원에게 보여 줄 문장이 아니다.

먼저 화면 문구로 상황을 구분한다. 권한 없음과 파일 없음은 다른 상황이다.

- "이 폴더에 접근할 권한이 없습니다" — 폴더 접근 권한 없음. 링크로 접근한 경우에 나오며, 목록으로 들어갈 때는 권한 없는 폴더가 목록에 보이지 않는다.
- "파일을 찾을 수 없습니다" — 파일이 삭제되었거나 이동됨.
- "공유 링크가 만료되었습니다" — 링크 만료.
- "파일을 열 수 없습니다. 잠시 후 다시 시도해 주세요" — 조회 오류(일시 장애). 이 런북의 원인 후보가 아니라 시스템 일시 장애로 따로 본다.

## 확인 항목

1. `check-file-error-message` — 화면에 표시된 문구. 위 구분의 출발점이다.
2. `check-file-access-route` — 폴더 목록에서 열었는지, 링크로 열었는지.
3. `check-file-recent-change` — 그 파일이 최근 삭제되거나 이동됐는지 아는지.
4. `check-file-link-received` — 공유 링크를 받은 시점. 링크 공유의 기본 만료일은 7일, 최대 30일이다.

## 가능 원인 후보

- `cause-file-folder-no-access` — 폴더 접근 권한이 없다. 권한은 폴더 단위로 부여된다.
- `cause-file-moved-deleted` — 파일이 삭제되었거나 다른 위치로 이동되었다. 삭제한 파일은 휴지통에 30일 보관된다.
- `cause-file-link-expired` — 공유 링크의 만료일이 지났다. 기본 만료일은 7일이다.

위 후보는 가능성일 뿐이며 확인 결과로만 좁힌다.

## 결과 해석

- 문구가 "이 폴더에 접근할 권한이 없습니다"이면 권한 문제로 본다. 링크로 접근했는지, 문의자에게 해당 폴더 접근 권한이 부여돼 있는지 확인한다.
- 문구가 "파일을 찾을 수 없습니다"이면 권한 문제가 아니다. 파일이 삭제되었거나 이동되었는지 확인하고, 삭제라면 휴지통에 있는지 본다.
- 문구가 "공유 링크가 만료되었습니다"이면 링크를 받은 시점과 만료일을 대조한다.
- 문구를 정확히 기억하지 못하면 접근 경로(목록인지 링크인지)와 최근 변경 여부로 후보를 좁힌다.
- 어느 항목에서도 원인이 좁혀지지 않으면 원인을 확정하지 않고 조사를 이어 간다.

## 적용 조건과 한계

- 파일 공유의 파일·폴더 열람에만 쓴다. 폴더·파일 단위 접근 권한은 파일 공유 담당이며 로그인·SSO 문제는 공통 로그인 담당이다.
- 전자결재 첨부가 열리지 않는 문제와 증상이 비슷하지만 원인과 확인 항목이 다르다. 한쪽의 원인으로 다른 쪽을 확정하지 않는다.
- 정상 로그인에 성공했다는 사실만으로 모든 파일의 열람 권한이 있다고 판단하지 않는다.
