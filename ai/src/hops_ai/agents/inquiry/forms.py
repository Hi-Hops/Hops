"""의도별 문의 양식과 다음 질문 선택 규칙.

문의 유형마다 담당자가 처리하려면 필요한 정보가 다르다(Notion 입력 표준화 3.2).
이 모듈은 어떤 정보가 이미 확보됐는지 보고, 무엇을 물을지 또는 더 물을 수 없어
담당자에게 넘길지를 정한다. 규칙은 모델이 아니라 코드이고 Neo4j나 지식 검색이 없어도
동작한다(4.1). 모델은 원문에서 사실을 추출하는 데만 쓰며 이 판단을 바꾸지 못한다.

- 필드 이름은 공통 계약의 RequestDetails를 따른다. 계약에 없는 정보(역할, 빈도,
  영향 사용자 등)는 이번 단계에서 묻지 않는다. 필드 추가는 공통 계약 변경이라
  팀 합의가 필요하다.
- 확보했다는 것은 원문에 근거가 있다는 뜻이다. 모델이 추정으로 채운 값은 호출하는 쪽이
  확보 목록에 넣지 않는다.
- 사용자가 모른다고 한 필드는 미확인으로 두고 다시 묻지 않는다.
  이미 한 번 물은 필드도 반복해서 묻지 않는다.
- 질문 순서는 1) 시스템 2) 담당자가 처리하는 데 필요한 누락 정보 순이다(4.3).
  문제당 질문은 최대 5회다.
"""

from collections.abc import Collection, Mapping, Sequence
from dataclasses import dataclass
from typing import Literal

from hops_ai.contracts.types import QUESTION_LIMIT
from hops_ai.contracts.wire import Intent
from hops_ai.rules.intent_policy import INTENT_POLICIES

# 시스템은 RequestDetails 필드가 아니라 문제의 보고 시스템 후보로 확인한다.
SYSTEM = "system"

# 문제당 질문 한도의 정본은 contracts.types.QUESTION_LIMIT 하나다.
MAX_QUESTIONS_PER_PROBLEM = QUESTION_LIMIT
MAX_FIELDS_PER_QUESTION = 3

# 아래 세 표의 값은 의도별 정책 표(rules.intent_policy)가 정본이다. 여기서는 의도 이름으로
# 바로 찾을 수 있게 펼쳐 둘 뿐이며 값을 따로 정의하지 않는다.

# 의도별로 처리에 꼭 필요한 정보. 앞에 있을수록 먼저 묻는다.
REQUIRED_FIELDS: Mapping[str, tuple[str, ...]] = {
    intent: policy.required_fields for intent, policy in INTENT_POLICIES.items()
}

# 승인된 가이드를 먼저 찾을 수 있을 때(Knowledge 연결) 안내 전에 확보할 정보.
# 사용법은 PRD 기준으로 시스템과 하려는 작업·화면만 알면 검색을 시작할 수 있다.
# 검색 결과가 없을 때 담당자 전달에 필요한 나머지 정보는 그 뒤에 확인한다.
GUIDE_FIRST_FIELDS: Mapping[str, tuple[str, ...]] = {
    intent: policy.guide_first_fields
    for intent, policy in INTENT_POLICIES.items()
    if policy.guide_first_fields is not None
}

# 승인된 가이드를 찾지 못해(Knowledge EMPTY) 담당자에게 넘길 수 있는 상태가 됐을 때 확보할 정보.
# 사용법의 우선 확인 항목에 원하는 결과를 더한다. 계약에 없는 정보(사용자 역할 등)는 묻지 않는다.
HANDOFF_FIELDS: Mapping[str, tuple[str, ...]] = {
    intent: policy.handoff_fields
    for intent, policy in INTENT_POLICIES.items()
    if policy.handoff_fields is not None
}

# 담당자 전달용 질문에서만 쓰는 문구. 사용법에 맞지 않는 기본 문구를 대신한다.
HANDOFF_PROMPTS: Mapping[str, str] = {
    "expectedBehavior": "이 작업으로 최종적으로 하고 싶은 일이나 원하시는 결과는 무엇인가요?",
}

# 질문에 쓰는 문구. 한 곳에 모아 두어 말투를 바꿔도 규칙은 그대로 둔다.
# 조사 문제를 피하려고 문장 단위로 둔다.
FIELD_PROMPTS: Mapping[str, str] = {
    "taskOrScreen": "어떤 업무를 하려고 하셨거나 어느 화면을 사용 중이셨나요?",
    "currentWorkflow": "지금은 이 일을 어떻게 진행하고 계신가요?",
    "expectedBehavior": "원래 어떻게 동작하길 기대하셨나요?",
    "actualBehavior": "실제로는 어떻게 되나요? 오류 문구가 있으면 함께 알려 주세요.",
    "occurredAt": "언제부터, 또는 언제 그런 일이 있었나요?",
    "requestedAccess": "어떤 기능이나 권한이 필요하신가요?",
    "businessPurpose": "그 권한이 필요한 업무 목적은 무엇인가요?",
    "desiredChange": "어떻게 바뀌기를 바라시나요?",
}

# 담당자에게 보여 줄 미확인 항목 이름.
FIELD_LABELS: Mapping[str, str] = {
    SYSTEM: "보고 시스템",
    "taskOrScreen": "하려던 업무·화면",
    "currentWorkflow": "현재 진행 방식",
    "expectedBehavior": "기대한 동작",
    "actualBehavior": "실제 동작",
    "occurredAt": "발생 시점",
    "requestedAccess": "필요한 기능·권한",
    "businessPurpose": "업무 목적",
    "desiredChange": "바라는 변경",
}

type Action = Literal["ASK", "COMPLETE", "HANDOFF", "OUT_OF_SCOPE"]


@dataclass(frozen=True)
class QuestionPlan:
    """다음에 할 일 하나.

    ASK: fields(system 포함)를 묻는 text를 사용자에게 보낸다.
    COMPLETE: 필요한 정보를 모두 확보했다. 안내 또는 티켓 제안으로 넘어간다.
    HANDOFF: 더 물을 수 없다(질문 한도, 이미 물었거나 모른다고 답함).
        unknown을 미확인으로 보존해 담당자에게 넘긴다.
    OUT_OF_SCOPE: 지원 범위 밖이라 질문하지 않는다.
    """

    action: Action
    fields: tuple[str, ...] = ()
    text: str | None = None
    unknown: tuple[str, ...] = ()


def required_fields(
    intent: Intent, *, guide_first: bool = False, handoff: bool = False
) -> tuple[str, ...]:
    """의도별 필수 필드.

    handoff: 가이드를 찾지 못한 뒤 담당자 전달에 필요한 필드. guide_first보다 우선한다.
    guide_first: 안내를 먼저 찾을 수 있는 의도는 검색에 필요한 필드만 쓴다.
    """
    if handoff and intent in HANDOFF_FIELDS:
        return HANDOFF_FIELDS[intent]
    if guide_first and intent in GUIDE_FIRST_FIELDS:
        return GUIDE_FIRST_FIELDS[intent]
    return REQUIRED_FIELDS[intent]


def missing_fields(
    intent: Intent,
    known: Mapping[str, str | None],
    *,
    system_known: bool,
    unknown: Collection[str] = (),
    guide_first: bool = False,
    handoff: bool = False,
) -> list[str]:
    """아직 확보하지 못한 필수 필드를 묻는 순서대로 돌려준다.

    Args:
        known: 원문 근거가 있는 필드 값. 비어 있거나 공백뿐인 값은 확보한 것으로 보지 않는다.
        system_known: 문제의 보고 시스템 후보가 정해졌는지.
        unknown: 사용자가 모른다고 답한 필드. 누락 목록에서 빠지며 호출 측이 미확인으로 보존한다.
            system도 포함할 수 있다.
        guide_first: 승인 가이드를 먼저 찾을 수 있는지. 사용법은 검색에 필요한 필드만 요구한다.
        handoff: 승인 가이드를 찾지 못해 담당자 전달에 필요한 필드를 요구하는지.
    """
    if intent == "OUT_OF_SCOPE":
        return []
    missing = [] if system_known or SYSTEM in unknown else [SYSTEM]
    for name in required_fields(intent, guide_first=guide_first, handoff=handoff):
        value = known.get(name)
        if (value is None or not value.strip()) and name not in unknown:
            missing.append(name)
    return missing


def plan_next(
    intent: Intent,
    known: Mapping[str, str | None],
    *,
    system_known: bool,
    system_names: Sequence[str] = (),
    question_count: int = 0,
    asked: Collection[str] = (),
    unknown: Collection[str] = (),
    guide_first: bool = False,
    handoff: bool = False,
) -> QuestionPlan:
    """확보한 정보를 보고 다음 행동을 정한다.

    Args:
        system_names: 시스템을 물을 때 보여 줄 지원 시스템 이름.
        question_count: 이 문제에 이미 한 질문 수. Spring이 저장한 값이다.
        asked: 이미 한 번 물은 필드. 답을 못 얻었어도 같은 필드를 다시 묻지 않는다.
        unknown: 사용자가 모른다고 답한 필드.
        guide_first: 승인 가이드를 먼저 찾을 수 있는지. 참이면 사용법은 시스템과 하려는
            작업·화면만 확보하면 COMPLETE가 되어 Knowledge 검색으로 넘어간다.
        handoff: 가이드를 찾지 못한 뒤인지. 참이면 담당자 전달용 항목을 확보하고 질문 문구도
            그에 맞게 쓴다.
    """
    if intent == "OUT_OF_SCOPE":
        return QuestionPlan("OUT_OF_SCOPE")
    missing = missing_fields(
        intent,
        known,
        system_known=system_known,
        unknown=unknown,
        guide_first=guide_first,
        handoff=handoff,
    )
    if not missing:
        if not system_known:
            # 시스템을 모른다고 답했다. 임의 담당자에게 배정하지 않고
            # 시스템 확인 대기로 넘기도록 미확인으로 알린다.
            return QuestionPlan("HANDOFF", unknown=tuple(unknown))
        return QuestionPlan("COMPLETE", unknown=tuple(f for f in unknown if f != SYSTEM))

    askable = [name for name in missing if name not in asked]
    if question_count >= MAX_QUESTIONS_PER_PROBLEM or not askable:
        # 더 물을 수 없다. 모르는 값은 지어내지 않고 미확인으로 넘긴다.
        return QuestionPlan("HANDOFF", unknown=tuple(missing))

    fields = tuple(askable[:MAX_FIELDS_PER_QUESTION])
    # 담당자 전달용 문구는 그 항목을 따로 정의한 의도에서만 쓴다.
    handoff_prompts = handoff and intent in HANDOFF_FIELDS
    return QuestionPlan(
        "ASK", fields=fields, text=compose_question(fields, system_names, handoff=handoff_prompts)
    )


def compose_question(
    fields: Sequence[str], system_names: Sequence[str] = (), *, handoff: bool = False
) -> str:
    """필드 목록을 한 번의 질문 문구로 만든다. 같은 입력이면 항상 같은 문구다."""
    parts = [_prompt(name, system_names, handoff=handoff) for name in fields]
    if len(parts) == 1:
        return parts[0]
    lines = "\n".join(f"{index}. {part}" for index, part in enumerate(parts, start=1))
    return f"처리에 필요한 내용을 알려 주시겠어요?\n{lines}"


def _prompt(name: str, system_names: Sequence[str], *, handoff: bool = False) -> str:
    if handoff and name in HANDOFF_PROMPTS:
        return HANDOFF_PROMPTS[name]
    if name == SYSTEM:
        if system_names:
            return f"어느 시스템에 대한 문의인가요? ({' / '.join(system_names)})"
        return "어느 시스템에 대한 문의인가요?"
    return FIELD_PROMPTS[name]
