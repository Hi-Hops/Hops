import pytest
from pydantic import ValidationError

from hops_ai.agents.baseline.decision import Decision, FinalResult, ToolArgument

FINAL = FinalResult(answer="답", problems=[], ticketProposals=[])


def make(action="FINISH", **overrides) -> dict:
    fields = {
        "action": action,
        "query": None,
        "toolName": None,
        "toolArguments": [],
        "final": None,
    }
    fields.update(overrides)
    return fields


def test_each_action_with_its_own_fields_is_valid() -> None:
    Decision(**make("SEARCH", query="VPN 오류"))
    Decision(
        **make(
            "DIAGNOSE",
            toolName="read_service_status",
            toolArguments=[ToolArgument(name="serviceId", value="vpn")],
        )
    )
    Decision(**make("FINISH", final=FINAL))


@pytest.mark.parametrize(
    "fields",
    [
        make("SEARCH"),  # query가 없다
        make("SEARCH", query="  "),
        make("DIAGNOSE"),  # 도구 이름이 없다
        make("DIAGNOSE", toolName=" "),
        make("FINISH"),  # final이 없다
    ],
)
def test_an_action_without_its_required_field_is_rejected(fields) -> None:
    with pytest.raises(ValidationError):
        Decision(**fields)


@pytest.mark.parametrize(
    "fields",
    [
        make("SEARCH", query="VPN", final=FINAL),
        make("SEARCH", query="VPN", toolName="read_service_status"),
        make("SEARCH", query="VPN", toolArguments=[ToolArgument(name="a", value="b")]),
        make("DIAGNOSE", toolName="read_service_status", query="VPN"),
        make("DIAGNOSE", toolName="read_service_status", final=FINAL),
        make("FINISH", final=FINAL, query="VPN"),
        make("FINISH", final=FINAL, toolName="read_service_status"),
    ],
)
def test_fields_of_another_action_are_rejected(fields) -> None:
    with pytest.raises(ValidationError):
        Decision(**fields)


def test_all_fields_must_be_sent_even_when_empty() -> None:
    with pytest.raises(ValidationError):
        Decision.model_validate({"action": "SEARCH", "query": "VPN"})


def test_unknown_action_and_unknown_field_are_rejected() -> None:
    with pytest.raises(ValidationError):
        Decision(**make("RESTART_SERVER"))
    with pytest.raises(ValidationError):
        Decision(**make("FINISH", final=FINAL), extra="x")


def test_arguments_become_a_dict_and_duplicates_are_detected() -> None:
    decision = Decision(
        **make(
            "DIAGNOSE",
            toolName="read_service_status",
            toolArguments=[
                ToolArgument(name="serviceId", value="vpn"),
                ToolArgument(name="detail", value="true"),
            ],
        )
    )
    duplicated = Decision(
        **make(
            "DIAGNOSE",
            toolName="read_service_status",
            toolArguments=[
                ToolArgument(name="serviceId", value="vpn"),
                ToolArgument(name="serviceId", value="mail"),
            ],
        )
    )

    assert decision.arguments() == {"serviceId": "vpn", "detail": "true"}
    assert not decision.has_duplicate_arguments()
    assert duplicated.has_duplicate_arguments()
