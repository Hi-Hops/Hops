"""의도별 정책 표. 의도마다 달라지는 규칙의 정본을 한 곳에 둔다.

양식 질문(forms), Inquiry, Knowledge, Ticket, 제안 검증은 이 표를 읽는다. 의도나 처리 유형
이름(`"KNOWLEDGE_QA"` 등)을 직접 비교하지 않는다. 새 의도를 추가하면 이 표에 항목을 더하고, 항목이
없거나 모순이면 이 모듈을 불러오는 시점(서버 시작)에 실패한다. 잘못된 표로 요청을 받지 않는다.

이 모듈은 값만 정한다. 질문 문구(`FIELD_PROMPTS`)와 담당자용 이름(`FIELD_LABELS`)은 문장이라
양식 모듈에 둔다. 에이전트·Supervisor 패키지를 불러오지 않는다.
"""

from collections.abc import Mapping
from dataclasses import dataclass
from types import MappingProxyType
from typing import Literal, get_args

from hops_ai.contracts.wire import Intent, ProcessingType

KnowledgeKind = Literal["GUIDE", "POLICY", "RUNBOOK", "CASE"]


@dataclass(frozen=True)
class IntentPolicy:
    """한 의도의 정책.

    processing_type: 의도에서 규칙으로 정하는 처리 유형. 모델이 정하지 않는다.
    required_fields: 처리에 꼭 필요한 정보. 앞에 있을수록 먼저 묻는다(시스템은 항상 먼저).
    guide_first_fields: 승인 가이드를 먼저 찾을 수 있을 때 안내 전에 확보할 정보. 없으면 None.
    handoff_fields: 가이드를 찾지 못해 담당자에게 넘길 때 확보할 정보. 없으면 None.
    ticketable: 이 처리 유형을 담당자가 티켓으로 처리하는지.
    answers_with_guide: 이 처리 유형이 승인된 가이드 안내로 끝나는지. 참이면 가이드를 찾지
        못한 경우에 한해 담당자 검토(REVIEW_REQUIRED) 티켓을 허용한다.
    knowledge_kinds: 검색할 지식 종류의 **기본값**(초안). 비어 있으면 검색하지 않는 것이 기본이다.
        접근 범위가 아니다. 직원 상담의 기본 프로필은 이 값과 무관하게 직원 공개 자료만 읽는다.
        아직 어떤 코드도 이 값을 읽지 않으며, 검색·평가 단계가 읽을 때 값을 확정한다.
    """

    processing_type: ProcessingType
    required_fields: tuple[str, ...]
    ticketable: bool
    answers_with_guide: bool = False
    guide_first_fields: tuple[str, ...] | None = None
    handoff_fields: tuple[str, ...] | None = None
    knowledge_kinds: frozenset[KnowledgeKind] = frozenset()


INTENT_POLICIES: Mapping[Intent, IntentPolicy] = MappingProxyType(
    {
        "HOW_TO": IntentPolicy(
            processing_type="KNOWLEDGE_QA",
            required_fields=("taskOrScreen", "currentWorkflow"),
            guide_first_fields=("taskOrScreen",),
            handoff_fields=("taskOrScreen", "expectedBehavior"),
            ticketable=False,
            answers_with_guide=True,
            knowledge_kinds=frozenset({"GUIDE", "POLICY"}),
        ),
        "ACCESS_REQUEST": IntentPolicy(
            processing_type="SERVICE_REQUEST",
            required_fields=("requestedAccess", "businessPurpose"),
            ticketable=True,
            knowledge_kinds=frozenset({"GUIDE", "POLICY"}),
        ),
        "OBSERVED_SYMPTOM": IntentPolicy(
            processing_type="INVESTIGATION",
            required_fields=("taskOrScreen", "expectedBehavior", "actualBehavior", "occurredAt"),
            ticketable=True,
            knowledge_kinds=frozenset({"GUIDE", "RUNBOOK", "CASE"}),
        ),
        "FEATURE_REQUEST": IntentPolicy(
            processing_type="CHANGE_REQUEST",
            required_fields=("currentWorkflow", "desiredChange"),
            ticketable=True,
        ),
        "IMPROVEMENT_REQUEST": IntentPolicy(
            processing_type="CHANGE_REQUEST",
            required_fields=("currentWorkflow", "desiredChange"),
            ticketable=True,
        ),
        # 의도를 모르면 무엇을 하려는지부터 확인한다. 새 장애(INCIDENT)는 확정하지 않는다.
        "UNKNOWN": IntentPolicy(
            processing_type="REVIEW_REQUIRED",
            required_fields=("taskOrScreen",),
            ticketable=True,
        ),
        "OUT_OF_SCOPE": IntentPolicy(
            processing_type="OUT_OF_SCOPE",
            required_fields=(),
            ticketable=False,
        ),
    }
)


def validate_policies(policies: Mapping[str, IntentPolicy]) -> None:
    """표가 모든 의도를 정확히 한 번씩 담고 서로 모순되지 않는지 확인한다.

    Raises:
        ValueError: 의도가 빠졌거나 알 수 없는 의도가 있거나, 같은 처리 유형을 쓰는 의도끼리
            티켓 가능·안내 처리 여부가 다르거나, 같은 필드 이름이 중복된 경우.
    """
    expected = set(get_args(Intent))
    missing = expected - set(policies)
    extra = set(policies) - expected
    if missing or extra:
        raise ValueError(
            f"의도별 정책 표가 의도 목록과 다르다: 빠짐={sorted(missing)}, 모름={sorted(extra)}"
        )
    flags: dict[str, tuple[bool, bool]] = {}
    for intent, policy in policies.items():
        pair = (policy.ticketable, policy.answers_with_guide)
        if flags.setdefault(policy.processing_type, pair) != pair:
            raise ValueError(f"같은 처리 유형({policy.processing_type})의 정책이 의도마다 다르다")
        for name in ("required_fields", "guide_first_fields", "handoff_fields"):
            fields = getattr(policy, name)
            if fields is not None and len(fields) != len(set(fields)):
                raise ValueError(f"{intent}의 {name}에 같은 필드가 중복됐다")


validate_policies(INTENT_POLICIES)

# 담당자가 티켓으로 처리하는 처리 유형. 안내로 끝나는 유형과 범위 밖은 해당하지 않는다.
TICKETABLE_TYPES: frozenset[ProcessingType] = frozenset(
    policy.processing_type for policy in INTENT_POLICIES.values() if policy.ticketable
)
# 승인된 가이드 안내로 끝나는 처리 유형.
GUIDE_TYPES: frozenset[ProcessingType] = frozenset(
    policy.processing_type for policy in INTENT_POLICIES.values() if policy.answers_with_guide
)


def policy_for(intent: Intent) -> IntentPolicy:
    """의도의 정책. 표는 시작할 때 모든 의도를 담는지 확인하므로 항목이 항상 있다."""
    return INTENT_POLICIES[intent]


def is_ticketable(processing_type: str) -> bool:
    """문제의 처리 유형을 담당자가 티켓으로 처리하는지."""
    return processing_type in TICKETABLE_TYPES


def is_guide_type(processing_type: str) -> bool:
    """문제의 처리 유형이 승인된 가이드 안내로 끝나는 유형인지."""
    return processing_type in GUIDE_TYPES
