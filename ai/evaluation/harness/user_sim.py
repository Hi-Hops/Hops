"""규칙 기반 사용자 모사. 모델이 아니므로 같은 질문에는 항상 같은 답이 나간다.

`ASKED_FIELD_ONLY`: AI가 물은 필드에 해당하는 값만 답한다. 묻지 않은 정보를 먼저 주지 않아야 질문
선택 규칙을 시험할 수 있다. 사용자가 모르는 필드는 "모르겠다"는 문장으로 답하며, 이 문장이 원문에
남아 AI가 미확인을 보존하는 근거가 된다.
"""

from collections.abc import Sequence
from dataclasses import dataclass

from evaluation.harness.catalog import SYSTEM_NAME_BY_CODE
from evaluation.harness.scenario import Scenario
from hops_ai.contracts.wire import QuestionProposal

# 질문 필드 이름. 시스템은 RequestDetails 필드가 아니라 별도 질문 필드다.
SYSTEM_FIELD = "system"
SYSTEM_CODE_FIELD = "systemCode"

_FIELD_PHRASES = {
    SYSTEM_FIELD: "사용 중인 시스템",
    "taskOrScreen": "하려던 업무나 화면",
    "currentWorkflow": "지금 진행하는 방식",
    "expectedBehavior": "기대한 동작",
    "actualBehavior": "실제 동작",
    "occurredAt": "발생 시점",
    "requestedAccess": "필요한 기능이나 권한",
    "businessPurpose": "업무 목적",
    "desiredChange": "바라는 변경",
}


@dataclass(frozen=True)
class UserReply:
    text: str
    # 모른다고 답한 (문제, 필드)
    unknown: tuple[tuple[str, str], ...]


def _answer(scenario: Scenario, name: str) -> str | None:
    """필드에 대한 답. 모르거나 아는 값이 없으면 None이다."""
    # 시스템 질문(`system`)의 모름 선언은 시나리오에서 `systemCode`로 쓴다.
    if (SYSTEM_CODE_FIELD if name == SYSTEM_FIELD else name) in scenario.unknown_fields:
        return None
    if name == SYSTEM_FIELD:
        code = scenario.user_knowledge.get("systemCode")
        system = SYSTEM_NAME_BY_CODE.get(code or "")
        return f"{system}입니다." if system else None
    value = scenario.user_knowledge.get(name)
    return value.strip() if value and value.strip() else None


def reply_to(scenario: Scenario, questions: Sequence[QuestionProposal], turn: int = 1) -> UserReply:
    """질문한 필드에만 답하는 사용자 응답을 만든다.

    `turn`은 AI가 질문을 한 횟수(1부터)다. 시나리오에 이 turn의 정정이 있으면 응답 끝에 붙인다.
    """
    if scenario.reveal_rule != "ASKED_FIELD_ONLY":
        raise ValueError(f"지원하지 않는 revealRule: {scenario.reveal_rule}")
    sentences: list[str] = []
    unknown: list[tuple[str, str]] = []
    answered: set[str] = set()
    for question in questions:
        for name in question.requestedFields:
            if name in answered:
                continue
            answered.add(name)
            answer = _answer(scenario, name)
            if answer is None:
                phrase = _FIELD_PHRASES.get(name, name)
                sentences.append(f"{phrase}은(는) 잘 모르겠어요.")
                unknown.append((question.problemRef, name))
            else:
                sentences.append(answer if answer.endswith((".", "요", "다")) else f"{answer}.")
    if not sentences:
        sentences.append("잘 모르겠어요.")
    sentences.extend(item.text for item in scenario.corrections if item.after_turn == turn)
    return UserReply(text=" ".join(sentences), unknown=tuple(unknown))
