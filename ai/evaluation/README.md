# AI 상담 평가 하네스

정리 전후의 동작을 같은 기준으로 비교하려고 만든 평가 도구다. 운영 코드(`src/hops_ai`)와 분리된 위치이며 AI 런타임과 실행 이미지에 들어가지 않는다. 성질 목록과 판정 방식은 [평가 방식](../../docs/knowledge/EVALUATION.md), 시나리오 형식은 [지식 데이터 형식](../../docs/knowledge/DATA_FORMAT.md)을 따른다.

## 구성

| 위치 | 역할 |
|---|---|
| `harness/checks.py` | 안전 성질 P1~P11 검사기. 실행 기록만 보는 순수 함수이며 모델을 부르지 않는다 |
| `harness/runner.py` | 시나리오를 끝까지 재생한다. AI가 질문하면 규칙 사용자가 답하고 다음 실행을 이어 간다 |
| `harness/user_sim.py` | 규칙 기반 사용자 모사(`ASKED_FIELD_ONLY`). 같은 질문에는 같은 답이 나간다 |
| `harness/spring_stub.py` | Spring이 AI 결과로 다음 실행 입력을 만드는 일을 흉내 내는 얇은 대역 |
| `harness/recorders.py` | 런타임 코드를 바꾸지 않고 역할 호출(검색 상태·근거)과 모델 호출 수를 기록 |
| `harness/scenario.py` | 시나리오 JSON 형식과 검증 |
| `harness/budget.py` | 평가 전체의 모델 호출 상한(Spring의 실행당 한도와 별개). 호출 전에 소모해 상한을 넘는 원격 호출이 일어나지 않게 한다 |
| `pilot.py` | 실제 모델로 시나리오를 한 번씩 재생하는 실행 도구. 호출·토큰·시간·위반과 실행 조건을 보고한다 |
| `harness/trace_io.py` | 실행 기록(`RunTrace`)을 JSON으로 저장·복원. 원본이 있어야 검사기를 고친 뒤 같은 기록을 다시 판정할 수 있다 |
| `recheck.py` | 저장된 기록을 현재 검사기로 다시 판정한다(모델 호출 없음) |
| `harness/validate.py` | 시나리오·정답 형식·정합성 검사, 정본 문서의 표(문서 카탈로그·시나리오 카탈로그) 읽기 |
| `baselines/` | 정리 전 구조의 실제 모델 기준선(개발용 12개 × 1회)의 원본 보고서와 해석. 비교의 출발점이며 일반 성능이 아니다 |
| `scenarios/`, `golds/` | 시나리오 20개(`DEV` 12·`TEST` 8)와 평가 정답, 파일 이름은 `scenarioId`. 정답은 런타임이 읽지 못하는 이 위치에만 둔다 |

## 읽을 때 알아둘 것

- **Fake 결과는 실제 모델 품질이 아니다.** `uv run pytest`의 하네스 테스트는 검사기와 재생기가 맞게 이어지는지만 본다. 행동은 실제 모델로 따로 측정하고, 그 결과는 모델·프롬프트 버전·데이터 버전·반복 횟수와 함께 적는다.
- **Spring 대역은 하네스의 가정이다.** 지금 Spring은 문제·질문을 저장해 다음 실행 입력에 돌려주지 않는다. 대역이 가정한 규칙(새 문제 ID 부여, 질문 횟수 증가, 인용한 메시지를 문제에 연결, 답한 뒤 문제는 `ANALYZING`)은 실제 구현이 생기면 대조해 바꾼다.
- **검사기가 위반을 찾으면 기록만 한다.** 고치지 않고 별도 결함으로 분리한다.
- 시나리오의 `userKnowledge` 값은 사용자가 그대로 말할 문장으로 쓴다. 모르는 필드는 `unknownFields`에 둔다.
- **아직 재생하지 않는 것.** 시나리오 20(`guide-unresolved`)의 `followUp`은 멀티턴 작업(#189)에서 재생을 지원하기 전까지 파일에만 있고 첫 실행만 재생한다. 시나리오 12(`retrieval-fault`)의 `faults`는 호출자가 검색 역할에 오류를 주입해야 한다. 정답의 `assessment`는 Knowledge 충분성 판정이 생기기 전(#181)에는 채점에 쓰지 않는다.
- **질문 5회 한도(P8)는 시나리오로 도달시키지 못한다.** 지금 규칙에서는 한 문제의 필수 필드가 최대 5개(시스템 포함)이고 이미 물은 필드를 다시 묻지 않으므로(P7) 질문 묶음이 5개가 되기 전에 필드가 소진된다. P8은 검사기 단위 테스트로만 확인한다.
- `TEST` 시나리오(13~20)는 동결한다. 프롬프트·규칙을 조정하는 데 쓰지 않는다.

## 실행

```bash
cd ai
uv run pytest tests/unit/test_eval_*.py
uv run python -m evaluation.harness.validate
```

두 번째 명령은 시나리오·정답 파일을 검사한다(모델을 부르지 않는다). 통과해도 내용이 사실에 맞는다는 뜻은 아니며 사람이 검수한다.

**아래 `evaluation.pilot` 명령은 실제 모델을 호출하며 비용이 든다.** 실제 모델 호출은 사용자가 모델·호출 상한·비용을 승인한 뒤 직접 실행한다. 진행 상황은 stderr에 나오고 `--out`에는 시나리오가 끝날 때마다 저장된다. 시나리오당 시간 제한 기본값은 240초다.

```bash
OPENAI_API_KEY=... uv run python -m evaluation.pilot --split DEV --max-calls 150 --out /tmp/baseline-dev.json --trace-dir evaluation/baselines/traces
OPENAI_API_KEY=... uv run python -m evaluation.pilot system-correction ea-notify-improvement --max-calls 40
```

`--trace-dir`을 주면 시나리오마다 원본 실행 기록(`<시나리오ID>.trace.json`)을 저장한다. 대화 전문이 들어가므로 합성 시나리오에서만 쓴다. 검사기를 고친 뒤에는 모델을 다시 부르지 않고 저장된 기록을 재판정한다. 위반이 있으면 종료 코드가 1이다.

```bash
uv run python -m evaluation.recheck evaluation/baselines/traces
```
