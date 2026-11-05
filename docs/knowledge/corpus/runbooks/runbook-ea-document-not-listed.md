---
documentKey: runbook-ea-document-not-listed
kind: RUNBOOK
title: 전자결재 문서가 문서함 목록에 보이지 않을 때
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
  - id: sym-ea-doc-not-listed
    label: 문서가 문서함에 보이지 않음
    featureCode: DOCUMENT_VIEW
    causes:
      - id: cause-ea-period-out-of-range
        label: 문서의 기안일이 조회 기간 밖
        checks: [check-ea-draft-date, check-ea-search-period]
      - id: cause-ea-no-view-permission
        label: 문의자가 기안자·결재선·참조에 없어 열람 권한이 없음
        checks: [check-ea-participation, check-ea-view-permission-record]
      - id: cause-ea-draft-not-submitted
        label: 상신하지 않은 임시 저장 문서
        checks: [check-ea-submit-state]
      - id: cause-ea-filter-narrow
        label: 입력한 상태·제목 키워드 조건이 좁음
        checks: [check-ea-filter-condition]
checks:
  - id: check-ea-search-period
    label: 문의자가 설정한 조회 기간
    employeeDisclosable: true
  - id: check-ea-draft-date
    label: 찾는 문서를 기안한 시기
    employeeDisclosable: true
  - id: check-ea-filter-condition
    label: 조회할 때 입력한 상태·제목 키워드
    employeeDisclosable: true
  - id: check-ea-submit-state
    label: 문서를 상신했는지, 임시 저장 상태인지
    employeeDisclosable: true
  - id: check-ea-participation
    label: 문의자가 그 문서의 기안자인지, 결재선·참조에 있는지
    employeeDisclosable: true
  - id: check-ea-view-permission-record
    label: 시스템에 남은 문서의 기안자·결재선·참조 기록
    employeeDisclosable: false
---

## 증상

문의자가 "문서가 전자결재 `문서함 > 전체 문서` 목록에 나오지 않는다"고 말한다. 대상 기능은 문서 조회(`DOCUMENT_VIEW`)이며 증상 `sym-ea-doc-not-listed`다. 이 문서는 담당자용이며 직원에게 보여 줄 문장이 아니다.

먼저 상황을 구분한다. 목록이 비어 "조건에 맞는 문서가 없습니다"가 나오는 것(결과 없음), 직접 링크로 열었더니 "열람 권한이 없는 문서입니다"가 나오는 것(권한 거부), "문서를 불러오지 못했습니다"가 나오는 것(조회 오류)은 서로 다른 상황이다. 조회 오류는 이 런북의 원인 후보가 아니라 시스템 일시 장애로 따로 본다.

## 확인 항목

아래 순서로 확인한다. 문의자가 모르면 "모름"으로 기록하고 다음 항목으로 넘어간다.

1. `check-ea-search-period` — 문의자가 설정한 조회 기간. 기본값은 최근 3개월이고 최대 12개월이다.
2. `check-ea-draft-date` — 찾는 문서를 기안한 시기. 조회 기간은 **기안일** 기준이다.
3. `check-ea-filter-condition` — 조회할 때 입력한 상태와 제목 키워드.
4. `check-ea-submit-state` — 문서를 상신했는지, 임시 저장 상태인지.
5. `check-ea-participation` — 문의자가 기안자인지, 결재선·참조에 포함됐는지.
6. `check-ea-view-permission-record` — 시스템에 남은 문서의 기안자·결재선·참조 기록. 문의자에게 묻는 항목이 아니라 담당자가 시스템에서 확인한다.

## 가능 원인 후보

- `cause-ea-period-out-of-range` — 기안일이 조회 기간 밖이다. 임시 저장해 둔 지 오래된 문서를 최근에 상신해도 기안일이 기간 밖이면 목록에 나오지 않는다.
- `cause-ea-no-view-permission` — 문의자가 기안자·결재선·참조에 없어서 볼 수 있는 문서가 아니다.
- `cause-ea-draft-not-submitted` — 임시 저장(`DRAFT`) 문서는 `문서함 > 전체 문서`에 나타나지 않고 `기안함 > 임시 저장`에만 있다.
- `cause-ea-filter-narrow` — 상태나 제목 키워드 조건이 문서와 맞지 않는다.

위 후보는 가능성일 뿐이며 확인 결과로만 좁힌다.

## 결과 해석

- 기안일이 조회 기간(기본 최근 3개월)에서 벗어나 있으면 기간 원인이 유력하다. 기안일을 포함하도록 기간을 넓혀 다시 조회했을 때 문서가 나타나는지 본다.
- 문의자가 기안자도 결재선·참조도 아니면 열람 권한이 없어서 보이지 않는 것이다. 목록에 나오지 않는 것이 정상이며 오류가 아니다. 시스템 기록에서 기안자·결재선·참조에 문의자가 없는지 확인한다.
- 문의자가 문서를 상신하지 않고 임시 저장만 했다면 `기안함 > 임시 저장`에 있는지 확인한다.
- 상태나 키워드를 넣었다면 조건을 비우고 다시 조회해 결과가 달라지는지 본다.
- 어느 항목에서도 원인이 좁혀지지 않으면 원인을 확정하지 않고 조사를 이어 간다.

## 적용 조건과 한계

- 문서 조회(`DOCUMENT_VIEW`)에서 문서가 목록에 보이지 않는 경우에만 쓴다. 결재 상태 확인이나 첨부 열람 문제는 다루지 않는다.
- 로그인이 반복되거나 세션이 끊기는 문제는 공통 로그인 담당이다.
- 확인 결과가 위 후보에 모두 맞지 않으면 이 런북으로 원인을 정하지 않는다.
