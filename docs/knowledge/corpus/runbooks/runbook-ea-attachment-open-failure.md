---
documentKey: runbook-ea-attachment-open-failure
kind: RUNBOOK
title: 전자결재 첨부 파일이 열리지 않을 때
systemCode: ELECTRONIC_APPROVAL
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
  - id: sym-ea-attach-not-open
    label: 첨부 파일이 열리지 않음
    featureCode: ATTACHMENT_VIEW
    causes:
      - id: cause-ea-preview-unsupported
        label: 미리보기를 지원하지 않는 형식
        checks: [check-ea-file-format]
      - id: cause-ea-attach-scanning
        label: 업로드 직후 보안 검사 중
        checks: [check-ea-upload-time]
      - id: cause-ea-storage-error
        label: 첨부 저장소의 일시 오류
        checks: [check-ea-error-message, check-ea-retry-result]
      - id: cause-ea-no-doc-permission
        label: 문서 열람 권한이 없어 첨부를 볼 수 없음
        # check-ea-participation은 runbook-ea-document-not-listed가 정의한다.
        checks: [check-ea-error-message, check-ea-participation]
checks:
  - id: check-ea-file-format
    label: 열리지 않는 첨부 파일의 형식
    employeeDisclosable: true
  - id: check-ea-upload-time
    label: 첨부 파일을 올린 시점
    employeeDisclosable: true
  - id: check-ea-error-message
    label: 화면에 표시된 문구
    employeeDisclosable: true
  - id: check-ea-retry-result
    label: 잠시 뒤 다시 시도해도 같은지
    employeeDisclosable: true
---

## 증상

문의자가 "전자결재 문서의 첨부 파일이 열리지 않는다"고 말한다. 대상 기능은 첨부 열람(`ATTACHMENT_VIEW`)이며 증상 `sym-ea-attach-not-open`이다. 이 문서는 담당자용이며 직원에게 보여 줄 문장이 아니다.

먼저 화면 문구로 상황을 구분한다. "첨부 파일 열람 권한이 없습니다"는 권한 거부이고, "첨부 파일을 열 수 없습니다"는 조회 오류다. [열기]가 눌리지 않고 [내려받기]만 되는 것과 "검사 중" 표시도 서로 다른 상황이다. 권한 거부를 조회 오류로, 조회 오류를 권한 거부로 바꿔 판단하지 않는다.

## 확인 항목

1. `check-ea-error-message` — 화면에 표시된 문구. 위 구분의 출발점이다.
2. `check-ea-file-format` — 열리지 않는 파일의 형식. 미리보기 형식은 pdf, png, jpg, txt다.
3. `check-ea-upload-time` — 파일을 올린 시점. 업로드 직후인지 본다.
4. `check-ea-retry-result` — 잠시 뒤 다시 시도해도 같은지.
5. `check-ea-participation` — 문의자가 문서의 기안자인지, 결재선·참조에 있는지. 정의는 `runbook-ea-document-not-listed`에 있다.

## 가능 원인 후보

- `cause-ea-preview-unsupported` — 미리보기 형식(pdf, png, jpg, txt)이 아니면 [열기]가 눌리지 않고 [내려받기]만 된다. 파일이 깨진 것이 아니다.
- `cause-ea-attach-scanning` — 업로드 직후 보안 검사를 최대 10분 하며, "검사 중"인 동안에는 열기와 내려받기가 모두 안 된다.
- `cause-ea-storage-error` — 저장소의 일시 오류로 "첨부 파일을 열 수 없습니다"가 나온다.
- `cause-ea-no-doc-permission` — 문서 열람 권한이 있어야 첨부를 볼 수 있다. 권한이 없으면 "첨부 파일 열람 권한이 없습니다"가 나온다.

위 후보는 가능성일 뿐이며 확인 결과로만 좁힌다.

## 결과 해석

- 문구가 "첨부 파일 열람 권한이 없습니다"이면 권한 거부다. 문의자가 문서의 기안자·결재선·참조에 있는지 확인한다.
- 문구가 "첨부 파일을 열 수 없습니다"이면 조회 오류다. 같은 파일이 시간이 지나도 계속 안 열리는지, 다른 첨부는 열리는지 본다.
- 파일 형식이 미리보기 형식이 아니면 [열기]가 안 눌리는 것이 정상이며, [내려받기]로 확인한다.
- 파일을 올린 지 10분 이내이고 "검사 중"이 보이면 검사가 끝날 때까지는 열 수 없는 정상 상태다. 검사가 끝난 뒤 다시 열어 본다.
- 어느 항목에서도 원인이 좁혀지지 않으면 원인을 확정하지 않고 조사를 이어 간다.

## 적용 조건과 한계

- 이 런북은 전자결재 문서의 첨부 열람에만 쓴다.
- 파일 공유의 파일이 열리지 않는 문제와 증상이 비슷하지만 원인과 확인 항목이 다르다. 전자결재의 첨부 저장소는 파일 공유와 자동으로 연결되지 않으므로, 한쪽의 원인으로 다른 쪽을 확정하지 않는다.
- 로그인이 반복되는 문제는 공통 로그인 담당이다.
