"""Supervisor 그래프의 상태. 노드가 채워 가며 마지막에 outcome이 남는다."""

from typing import TypedDict

from hops_ai.contracts.agent_1 import IntakeResult, TicketResult
from hops_ai.contracts.analysis import AnalysisInput, AnalysisOutcome


class SupervisorState(TypedDict, total=False):
    data: AnalysisInput
    intake: IntakeResult
    ticket: TicketResult
    outcome: AnalysisOutcome
