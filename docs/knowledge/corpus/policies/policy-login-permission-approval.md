---
documentKey: policy-login-permission-approval
kind: POLICY
title: 시스템 접근 권한 신청 승인 기준
systemCode: COMMON_LOGIN
accessScope: EMPLOYEE
version: 1
appliesToSystemVersion: null
applicability: "사내 시스템을 이용하려는 모든 임직원의 시스템 이용 권한 신청에 적용한다. 읽기·쓰기·관리 권한의 승인 기준을 정하며 파일 공유의 폴더·파일 단위 접근 권한은 파일 공유 범위 정책을 따른다."
dataSplit: RAG
origin: SYNTHETIC
reviewStatus: APPROVED
source:
  type: AUTHORED
  reference: null
# 처리가 지연되는 증상은 오류가 아니라 정해진 승인 단계라서 런북 없이 이 정책이 근거다(정본 6.1절, 열린 문제 6).
symptoms:
  - id: sym-login-permission-pending
    label: 권한 신청이 오래 승인되지 않음
    featureCode: PERMISSION_REQUEST
    causes:
      - id: cause-login-teamlead-confirm-missing
        label: 쓰기·관리 권한에 필요한 팀장 확인이 누락됨
        checks: [check-login-permission-level, check-login-teamlead-confirm]
checks:
  - id: check-login-permission-level
    label: 신청한 권한 종류(읽기·쓰기·관리)
    employeeDisclosable: true
  - id: check-login-teamlead-confirm
    label: 소속 팀장 확인을 요청했는지
    employeeDisclosable: true
---

## 규칙

- 시스템 접근 권한은 **읽기**, **쓰기**, **관리** 세 종류입니다.
- 신청서에는 대상 시스템, 필요한 기능·권한, **업무 목적**을 적습니다. 업무 목적이 없으면 신청할 수 없습니다.
- 읽기 권한은 대상 시스템의 주담당자가 승인합니다.
- 쓰기·관리 권한은 주담당자 승인에 더해 신청자 **소속 팀장의 확인**이 필요합니다. 팀장 확인이 끝나기 전까지 신청은 "접수"(`REQUESTED`) 상태에 머뭅니다.
- 처리 목표는 신청일로부터 2영업일입니다.
- 이미 가지고 있는 권한은 다시 신청할 수 없습니다.
- 필요한 기간을 적지 않으면 상시 권한으로 신청됩니다.

신청 상태는 "접수"(`REQUESTED`), "승인"(`APPROVED`), "반려"(`REJECTED`), "적용"(`APPLIED`)입니다.

## 적용 대상

- 사내 시스템(전자결재, 파일 공유 등)을 이용하려는 모든 임직원
- 이 정책은 시스템 이용 권한만 다룹니다. 파일 공유의 폴더·파일 단위 접근 권한은 파일 공유 범위 정책을 따릅니다.

## 예외

이 정책에는 정해진 예외가 없습니다.

## 신청 절차

신청 방법은 `내 정보 > 접근 권한 신청`에서 대상 시스템, 권한 종류, 업무 목적을 입력해 제출하는 것입니다. 쓰기·관리 권한은 신청 뒤 소속 팀장에게 확인을 요청해야 합니다.

## 근거

가상 업무 규정입니다. 신청 접수와 진행은 공통 로그인 담당자가 관리합니다.
