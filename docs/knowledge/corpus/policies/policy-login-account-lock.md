---
documentKey: policy-login-account-lock
kind: POLICY
title: 계정 잠금과 비밀번호 규칙
systemCode: COMMON_LOGIN
accessScope: EMPLOYEE
version: 1
appliesToSystemVersion: null
applicability: "사내 포털에 로그인하는 모든 임직원 계정에 적용한다. 비밀번호 오류에 따른 계정 잠금·해제와 비밀번호 규칙을 정한다."
dataSplit: RAG
origin: SYNTHETIC
reviewStatus: APPROVED
source:
  type: AUTHORED
  reference: null
---

## 규칙

- 비밀번호를 **5회 연속** 틀리면 계정이 잠깁니다.
- 잠긴 계정은 **30분 뒤 자동으로 풀립니다**.
- 30분을 기다리지 않고 바로 풀려면 [잠금 해제 요청]을 접수합니다. 공통 로그인 담당자가 처리합니다.
- 새 비밀번호는 **10자 이상**이며 영문, 숫자, 특수문자를 모두 조합해야 합니다.
- 직전에 쓴 3개의 비밀번호는 다시 쓸 수 없습니다.

## 적용 대상

사내 포털에 로그인하는 모든 임직원 계정

## 예외

이 정책에는 정해진 예외가 없습니다.

## 신청 절차

- 비밀번호를 잊은 경우: 로그인 화면의 [비밀번호 찾기]를 누르고 사번과 등록된 이메일을 확인하면 재설정 링크가 전달됩니다. 링크는 30분 동안 유효합니다.
- 계정이 잠긴 경우: 30분을 기다리거나 [잠금 해제 요청]을 접수합니다.

## 근거

가상 업무 규정입니다.
