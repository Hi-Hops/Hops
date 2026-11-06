---
documentKey: runbook-login-sso-loop
kind: RUNBOOK
title: 로그인 후 다시 로그인 화면으로 돌아올 때
systemCode: COMMON_LOGIN
accessScope: SYSTEM_STAFF
version: 1
appliesToSystemVersion: null
applicability: "로그인에 성공한 뒤 로그인 화면이 반복되는 경우에만 쓴다. 인증 실패, 계정 잠금, 세션 만료, 이용 권한 없음에는 쓰지 않는다."
dataSplit: RAG
origin: SYNTHETIC
reviewStatus: APPROVED
source:
  type: AUTHORED
  reference: null
symptoms:
  - id: sym-login-loop
    label: 로그인했는데 다시 로그인 화면으로 돌아옴
    featureCode: LOGIN_SSO
    causes:
      - id: cause-login-cookie-blocked
        label: 브라우저가 쿠키를 차단해 SSO 세션이 저장되지 않음
        checks: [check-login-browser-cookie, check-login-browser-other]
      - id: cause-login-clock-skew
        label: 단말 시각이 서버와 5분 넘게 어긋나 세션이 곧바로 만료로 판정됨
        checks: [check-login-device-time]
      - id: cause-login-linked-system-auth
        label: 연동 시스템의 SSO 세션 검증이 실패함
        checks: [check-login-portal-direct, check-login-reported-system]
checks:
  - id: check-login-browser-cookie
    label: 브라우저의 쿠키 차단 설정 여부
    employeeDisclosable: true
  - id: check-login-browser-other
    label: 다른 브라우저나 시크릿 창에서도 같은지
    employeeDisclosable: true
  - id: check-login-device-time
    label: 단말의 날짜·시간 설정이 맞는지
    employeeDisclosable: true
  - id: check-login-portal-direct
    label: 사내 포털에서 직접 로그인하면 되는지
    employeeDisclosable: true
  - id: check-login-reported-system
    label: 어느 시스템에서 로그인 화면이 반복되는지
    employeeDisclosable: true
---

## 증상

문의자가 "로그인했는데 다시 로그인 화면이 나온다"고 말한다. 대상 기능은 로그인·SSO(`LOGIN_SSO`)이며 증상 `sym-login-loop`다. 정상 흐름은 로그인 성공 뒤 대상 시스템 화면이 열리는 것이고, 다시 로그인 화면으로 돌아오는 것은 정상이 아니다. 이 문서는 담당자용이며 직원에게 보여 줄 문장이 아니다.

먼저 다른 상황과 구분한다.

- 사번이나 비밀번호가 틀려 "사번 또는 비밀번호가 올바르지 않습니다"가 나오는 것은 인증 실패다.
- 비밀번호를 5회 연속 틀려 계정이 잠긴 것은 계정 잠금이다.
- 비활동 30분이 지나 "세션이 만료되었습니다. 다시 로그인해 주세요"가 나오는 것은 정상적인 세션 만료다.
- "이 시스템을 사용할 권한이 없습니다"는 이용 권한이 없는 것이며 로그인 반복이 아니다.

문의자는 전자결재나 파일 공유처럼 다른 시스템에서 이 증상을 겪고 보고할 수 있다. 이때 보고 시스템과 책임 시스템이 다르다.

## 확인 항목

1. `check-login-reported-system` — 어느 시스템에서 로그인 화면이 반복되는지. 포털에서인지, 전자결재나 파일 공유에서인지.
2. `check-login-portal-direct` — 사내 포털에서 직접 로그인하면 정상인지.
3. `check-login-browser-cookie` — 브라우저의 쿠키 차단 설정 여부.
4. `check-login-browser-other` — 다른 브라우저나 시크릿 창에서도 같은지.
5. `check-login-device-time` — 단말의 날짜·시간 설정이 맞는지.

## 가능 원인 후보

SSO 세션은 브라우저의 포털 도메인 쿠키에 저장되고, 전자결재와 파일 공유는 이 세션을 검증해 이용을 허용한다.

- `cause-login-cookie-blocked` — 브라우저가 쿠키를 차단하면 로그인에 성공해도 세션이 저장되지 않아 로그인 화면이 반복된다.
- `cause-login-clock-skew` — 단말 시각이 서버와 5분 넘게 어긋나면 발급된 세션이 곧바로 만료로 판정되어 같은 증상이 반복된다.
- `cause-login-linked-system-auth` — 포털 로그인은 정상인데 특정 연동 시스템의 세션 검증이 실패한다. 포털에서 직접 로그인하면 되고 특정 시스템에서만 반복된다.

위 후보는 가능성일 뿐이며 확인 결과로만 좁힌다.

## 결과 해석

- 어느 브라우저에서나 포털 직접 로그인부터 반복되면 쿠키 차단이나 단말 시각 어긋남을 의심한다. 다른 브라우저나 시크릿 창에서는 되고 원래 브라우저에서만 안 되면 쿠키 차단이 유력하다.
- 단말 날짜·시간이 실제와 크게 다르면 시각 어긋남이 유력하다.
- 포털에서 직접 로그인하면 정상이고 특정 시스템에서만 반복되면 연동 시스템의 세션 검증을 의심한다.
- 전자결재에서 보고했더라도 원 맥락은 전자결재로 보존한다. 대화와 허용된 근거가 공통 로그인의 처리를 뒷받침할 때만 책임 시스템 변경을 제안한다. 의존 관계만 있거나 근거가 부족하면 보고 시스템 담당자가 최초로 조사한다.
- 어느 항목에서도 원인이 좁혀지지 않으면 원인을 확정하지 않고 조사를 이어 간다.

## 적용 조건과 한계

- 로그인 성공 뒤 로그인 화면이 반복되는 경우에만 쓴다. 인증 실패, 계정 잠금, 세션 만료, 이용 권한 없음에는 쓰지 않는다.
- 전자결재 첨부가 열리지 않는 문제는 이 증상과 별개다. 두 문제를 함께 보고해도 한쪽을 다른 쪽의 원인으로 확정하지 않는다.
- 로그인에 성공했다는 사실만으로 모든 문서·파일의 열람 권한이 있다고 판단하지 않는다.
