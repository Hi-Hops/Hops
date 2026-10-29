"""단일 에이전트의 판단 출력. 모델은 매 번 다음 행동 하나를 구조화해서 정한다.

- SEARCH: 지식 문서를 검색한다. 검색 범위(접근 필터)는 서버가 정하며 모델이 고르지 않는다.
- DIAGNOSE: 허용된 읽기 도구를 호출한다. 실제 허용 여부는 서버 정책과 Spring이 판단한다.
- FINISH: 분석을 끝내고 결과를 낸다.
모든 필드는 필수이고 해당 없는 값은 null 또는 빈 배열이다(다른 계약과 같은 규칙).
"""

from typing import Literal

from pydantic import model_validator

from hops_ai.contracts.agent_1 import IntakeProblem, TicketProposal
from hops_ai.contracts.types import ContractModel, NonEmptyText, Text


class ToolArgument(ContractModel):
    name: NonEmptyText
    value: Text


class FinalResult(ContractModel):
    answer: Text
    problems: list[IntakeProblem]
    ticketProposals: list[TicketProposal]


class Decision(ContractModel):
    action: Literal["SEARCH", "DIAGNOSE", "FINISH"]
    query: Text | None
    toolName: Text | None
    toolArguments: list[ToolArgument]
    final: FinalResult | None

    @model_validator(mode="after")
    def _fields_match_the_action(self) -> "Decision":
        search = self.action == "SEARCH"
        diagnose = self.action == "DIAGNOSE"
        finish = self.action == "FINISH"
        if search and not (self.query and self.query.strip()):
            raise ValueError("SEARCH는 query가 필요하다")
        if diagnose and not (self.toolName and self.toolName.strip()):
            raise ValueError("DIAGNOSE는 toolName이 필요하다")
        if finish and self.final is None:
            raise ValueError("FINISH는 final이 필요하다")
        if not search and self.query is not None:
            raise ValueError("query는 SEARCH에서만 쓴다")
        if not diagnose and (self.toolName is not None or self.toolArguments):
            raise ValueError("도구 필드는 DIAGNOSE에서만 쓴다")
        if not finish and self.final is not None:
            raise ValueError("final은 FINISH에서만 쓴다")
        return self

    def arguments(self) -> dict[str, str]:
        return {argument.name: argument.value for argument in self.toolArguments}

    def has_duplicate_arguments(self) -> bool:
        names = [argument.name for argument in self.toolArguments]
        return len(set(names)) != len(names)
