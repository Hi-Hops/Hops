# Knowledge dataset v0 팀 검수 체크리스트

현재 자동 검증은 JSON Schema·ID·hash·근거 참조·권한 상태를 확인한다. 내용의 업무 적합성은 팀 사람이 검수해야 한다.

검수 범위와 목표 수량은 [데이터셋 범위](DATASETS.md), 장애 정답과 문서 연결은 [장애·문의 시나리오](INCIDENT_SCENARIOS.md), 승인 이후 변경 규칙은 [지식 수명주기](KNOWLEDGE_LIFECYCLE.md)를 기준으로 한다.

## 1. 공통

- [ ] 가상 기업의 Windows 11 직원 환경과 Docker Compose 서버 환경이 이해된다.
- [ ] 사용자 서비스 `PORTAL`과 내부 컴포넌트 `PORTAL_API`·`PORTAL_DB` 구분에 동의한다.
- [ ] Backend와 DB의 기본 담당 경계에 동의한다.
- [ ] Agent가 코드·설정·배포·재시작·DB 쓰기를 수행하지 않는 데 동의한다.
- [ ] 모든 데이터가 가상·공개 근거 기반이며 실제 개인정보를 포함하지 않는다.

## 2. STAFF_RUNBOOK 11건

- [ ] 사용자 증상이 실제 LMS 문의 문장으로 자연스럽다.
- [ ] 각 원인 후보에 필요 신호와 반대 신호가 모두 있다.
- [ ] 같은 증상의 API·풀·DB 연결·잠금·쿼리 원인이 서로 구분된다.
- [ ] MFA·VPN·SMB 런북이 공개 근거 범위를 넘어서 원인을 확정하지 않는다.
- [ ] Wi-Fi·802.1X가 hold 상태에서 런북·청크로 생성되지 않았다.
- [ ] `guidance_steps`는 사용자가 안전하게 수행할 수 있다.
- [ ] `staff_resolution_steps`가 Agent 자동 실행으로 오해되지 않는다.
- [ ] 내부 SQL·스택·서버·사용자 정보가 사용자 응답에 노출되지 않는다.
- [ ] 근거가 부족하거나 안내 후 미해결이면 이관한다.

## 2-1. 일반 공개 근거 62건과 섹션 82건

- [ ] `include 12 / hold 4 / reject 46` 판정과 사유에 동의한다.
- [ ] 출처 URL이 commit SHA에 고정되고 CC BY 4.0 귀속 정보가 있다.
- [ ] 공개 원문은 한국어 가이드·런북 작성 근거이며 RAG 직접 적재 대상이 아니다.
- [ ] 직원 공개 후보 12개를 아직 공개 승인으로 해석하지 않는다.
- [ ] `NEVER_EXPOSE` 4개 섹션이 직원 검색과 답변에서 항상 제외된다.
- [ ] Graph 삭제·레지스트리 편집·권한 완화 절차가 직원 가이드에 들어가지 않는다.

## 3. EMPLOYEE_GUIDE 13건

- [ ] 직원이 이해할 수 있는 표현이다.
- [ ] 비밀번호·토큰·개인정보 입력을 요구하지 않는다.
- [ ] 저장·검색 버튼의 위험한 반복 클릭을 권하지 않는다.
- [ ] 요청 추적 번호·시각·메뉴처럼 필요한 정보만 수집한다.
- [ ] 해결되지 않으면 적절한 시점에 Hops 문의로 전환한다.
- [ ] IDENTITY·VPN·FILE_SHARE 가이드가 관리자 절차를 직원에게 노출하지 않는다.
- [ ] 공통 가이드 3건의 PRD 정책 설명이 실제 서비스 흐름과 일치한다.
- [ ] Wi-Fi·802.1X 가이드가 hold 상태에서 생성되지 않았다.

## 4. 용어·진단 신호 레지스트리

[IT 용어집](references/IT_GLOSSARY.md),
[IT 용어집 담당자 검수 결과](references/IT_GLOSSARY_REVIEW.md),
[오류·관측 신호 사전](references/ERROR_SIGNALS.md),
[진단 신호 담당자 검수 결과](references/ERROR_SIGNAL_REVIEW.md)를 함께 검토한다.

- [ ] 용어 50개의 정의가 팀 개발자와 시연 사용자에게 혼동을 주지 않는다.
- [ ] 용어 정의가 운영 명령·원시 로그·내부 설정·비밀값을 포함하지 않는다.
- [ ] AI 용어는 제품 설명에만 사용하고 장애 원인 근거로 사용하지 않는다.
- [ ] 오류 신호 55개가 “의미”와 “확정 제한”을 함께 갖는다.
- [ ] 런북의 `causes` 청크가 참조 신호의 역할·매칭 조건·의미·확정 제한과 레지스트리 해시를 포함한다.
- [ ] 비PORTAL 신호 28개가 확정된 런북·시나리오를 근거로 하며 새 서비스 범위를 만들지 않는다.
- [ ] HTTP 500·leak 로그·Seq Scan 같은 단일 신호로 원인을 확정하지 않는다.
- [ ] Tool 담당자가 필요한 읽기 결과 필드를 식별할 수 있다.
- [ ] 신호 레지스트리가 독립 RAG 청크나 임베딩으로 생성되지 않는다.
- [ ] Diagnosis Agent가 Vector DB와 신호 레지스트리를 직접 검색하지 않는다.

## 5. POLICY 5건

- [ ] Agent는 답변·진단·부서·티켓 초안만 제안하고 업무 상태를 직접 확정하지 않는다.
- [ ] 티켓 생성·부서 배정·재이관·해결 완료는 Spring 또는 사람이 최종 확정한다.
- [ ] 직원 답변은 `EMPLOYEE/QUOTABLE`만 사용하고 `STAFF/INTERNAL_ONLY` 원문을 노출하지 않는다.
- [ ] 해결 확인 전 티켓 종료 또는 CASE 승격을 하지 않는다.
- [ ] 시나리오의 숨겨진 정답과 평가 데이터를 RAG 근거로 사용하지 않는다.
- [ ] 정책 우선순위와 실제 Spring 검증 지점이 구현 가능한 형태다.

## 6. RAG 청크

- [ ] 같은 진단 단계를 중간에서 잘라 의미가 손실된 청크가 없다.
- [ ] 직원 가이드 청크는 `EMPLOYEE/QUOTABLE`이다.
- [ ] 런북 청크는 `STAFF/INTERNAL_ONLY`이며 신호 레지스트리는 독립 청크가 없다.
- [ ] IT 용어 정의 청크는 `EMPLOYEE/QUOTABLE`이다.
- [ ] 모든 청크가 현재 `ingest_status=PENDING`이다.
- [ ] 팀 승인 전 임베딩 인덱스에 넣지 않는다.

## 7. 리뷰 결과 기록

팀 승인 시 다음 변경을 하나의 리뷰 PR로 처리한다.

1. 변경 요청을 정본 JSON에 반영한다.
2. 청크와 manifest를 다시 생성한다.
3. 승인 문서의 `team_review_status`를 `APPROVED`로 변경한다.
4. RAG에 넣을 문서만 `ingest_status=APPROVED`로 변경한다.
5. 최초 1회 `python3 -m pip install -r datasets/knowledge/v0/requirements.txt`로 검증 의존성을 설치한다.
6. `python3 datasets/knowledge/v0/scripts/build_and_validate.py`를 실행한다.

Tool과 시나리오 상태는 이 리뷰에서 억지로 `READY/PASSED`로 바꾸지 않는다. 각각 실제 연결·재현 후 별도 갱신한다.
