---
documentKey: guide-ea-approval-status
kind: GUIDE
title: 전자결재 결재 상태와 결재선 탭 보는 법
systemCode: ELECTRONIC_APPROVAL
accessScope: EMPLOYEE
version: 1
appliesToSystemVersion: null
dataSplit: RAG
origin: SYNTHETIC
reviewStatus: APPROVED
source:
  type: AUTHORED
  reference: null
---

## 목적

전자결재 문서의 결재가 어디까지 진행됐는지 확인하는 방법과, 화면에 나오는 상태의 의미를 안내합니다.

## 전제

- 문서를 열람할 권한이 있어야 합니다. 내가 기안했거나 결재선·참조에 포함된 문서가 해당합니다.

## 절차

1. 문서함이나 기안함에서 문서를 열어 상세 화면으로 이동합니다.
2. `결재선` 탭을 엽니다.
3. 문서 상태와 결재자별 단계 상태를 확인합니다.

## 확인할 수 있는 결과

문서 상태는 다음 중 하나입니다.

| 상태 | 코드 | 의미 |
|---|---|---|
| 임시 저장 | `DRAFT` | 상신하지 않고 저장만 한 상태 |
| 상신 | `SUBMITTED` | 상신했고 아직 아무도 처리하지 않은 상태 |
| 결재 중 | `IN_PROGRESS` | 한 단계 이상 처리되었고 결재가 진행 중인 상태 |
| 승인 | `APPROVED` | 마지막 결재자까지 승인한 상태 |
| 반려 | `REJECTED` | 반려된 상태. 사유가 함께 표시됩니다 |
| 회수 | `WITHDRAWN` | 기안자가 회수한 상태 |

결재선의 단계 상태는 "대기"(`WAITING`), "승인"(`APPROVED`), "반려"(`REJECTED`)입니다.

상태는 `임시 저장 → 상신 → 결재 중 → 승인 또는 반려` 순서로 바뀝니다. "상신"이나 "결재 중"(첫 결재자가 처리하기 전)에서는 기안자가 회수하면 "회수"가 됩니다.

## 적용 조건과 한계

- 이 안내는 상태를 읽는 방법만 다룹니다. 결재 자체를 대신 처리하는 방법은 포함하지 않습니다.
- 열람 권한이 없는 문서의 결재선은 볼 수 없습니다.
