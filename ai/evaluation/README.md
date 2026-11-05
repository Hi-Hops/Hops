# AI 상담 평가 하네스

정리 전후의 동작을 같은 기준으로 비교하려고 만든 평가 도구다. 운영 코드(`src/hops_ai`)와 분리된 위치이며 AI 런타임과 실행 이미지에 들어가지 않는다. 성질 목록과 판정 방식은 [평가 방식](../../docs/knowledge/EVALUATION.md), 시나리오 형식은 [지식 데이터 형식](../../docs/knowledge/DATA_FORMAT.md)을 따른다.

## 구성

| 위치 | 역할 |
|---|---|
| `harness/checks.py` | 안전 성질 P1~P9 검사기. 실행 기록만 보는 순수 함수이며 모델을 부르지 않는다 |
| `harness/runner.py` | 시나리오를 끝까지 재생한다. AI가 질문하면 규칙 사용자가 답하고 다음 실행을 이어 간다 |
| `harness/user_sim.py` | 규칙 기반 사용자 모사(`ASKED_FIELD_ONLY`). 같은 질문에는 같은 답이 나간다 |
| `harness/spring_stub.py` | Spring이 AI 결과로 다음 실행 입력을 만드는 일을 흉내 내는 얇은 대역 |
| `harness/recorders.py` | 런타임 코드를 바꾸지 않고 역할 호출(검색 상태·근거)과 모델 호출 수를 기록 |
| `harness/scenario.py` | 시나리오 JSON 형식과 검증 |
| `scenarios/`, `golds/` | 시나리오 입력과 평가 정답(별도 파일). 정답은 런타임이 읽지 못하는 이 위치에만 둔다 |

## 읽을 때 알아둘 것

- **Fake 결과는 실제 모델 품질이 아니다.** `uv run pytest`의 하네스 테스트는 검사기와 재생기가 맞게 이어지는지만 본다. 행동은 실제 모델로 따로 측정하고, 그 결과는 모델·프롬프트 버전·데이터 버전·반복 횟수와 함께 적는다.
- **Spring 대역은 하네스의 가정이다.** 지금 Spring은 문제·질문을 저장해 다음 실행 입력에 돌려주지 않는다. 대역이 가정한 규칙(새 문제 ID 부여, 질문 횟수 증가, 인용한 메시지를 문제에 연결, 답한 뒤 문제는 `ANALYZING`)은 실제 구현이 생기면 대조해 바꾼다.
- **검사기가 위반을 찾으면 기록만 한다.** 고치지 않고 별도 결함으로 분리한다.
- 시나리오의 `userKnowledge` 값은 사용자가 그대로 말할 문장으로 쓴다. 모르는 필드는 `unknownFields`에 둔다.

## 실행

```bash
cd ai
uv run pytest tests/unit/test_eval_*.py
```
