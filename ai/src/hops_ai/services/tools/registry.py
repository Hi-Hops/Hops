"""사용할 수 있는 읽기 도구의 이름과 인자 규칙. 도구 인자는 모델이 만들므로 항상 검증한다."""

from collections.abc import Mapping
from dataclasses import dataclass


@dataclass(frozen=True)
class ToolSpec:
    name: str
    description: str
    required: frozenset[str] = frozenset()
    optional: frozenset[str] = frozenset()

    def argument_problem(self, arguments: Mapping[str, object]) -> str | None:
        """인자가 규칙에 맞으면 None, 아니면 문제를 설명하는 문구."""
        missing = self.required - arguments.keys()
        if missing:
            return f"필수 인자가 없다: {sorted(missing)}"
        unknown = arguments.keys() - self.required - self.optional
        if unknown:
            return f"허용하지 않은 인자가 있다: {sorted(unknown)}"
        return None


class ToolRegistry:
    def __init__(self, *specs: ToolSpec) -> None:
        self._specs: dict[str, ToolSpec] = {}
        for spec in specs:
            if spec.name in self._specs:
                raise ValueError(f"이미 등록된 도구다: {spec.name}")
            self._specs[spec.name] = spec

    def get(self, name: str) -> ToolSpec | None:
        return self._specs.get(name)

    @property
    def names(self) -> frozenset[str]:
        return frozenset(self._specs)
