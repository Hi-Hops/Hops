"""패키지 의존 방향. agents·rules·contracts는 supervisor를 불러오지 않아 서로 순환하지 않는다."""

import ast
import subprocess
import sys
from pathlib import Path

import pytest

import hops_ai

SOURCE = Path(hops_ai.__file__).parent
NEUTRAL_PACKAGES = ("agents", "rules", "contracts")

STANDALONE_MODULES = [
    "hops_ai.agents",
    "hops_ai.agents.base",
    "hops_ai.agents.inquiry",
    "hops_ai.agents.inquiry.readiness",
    "hops_ai.agents.knowledge",
    "hops_ai.agents.ticket",
    "hops_ai.agents.ticket.executing",
    "hops_ai.agents.curator",
    "hops_ai.agents.registry",
    "hops_ai.rules.progress",
    "hops_ai.rules.proposals",
    "hops_ai.contracts.state",
    "hops_ai.supervisor",
    "hops_ai.supervisor.validation",
    "hops_ai.supervisor.merge",
    "hops_ai.supervisor.decision",
    "hops_ai.supervisor.execution",
]


def run_python(code: str) -> subprocess.CompletedProcess[str]:
    return subprocess.run(
        [sys.executable, "-c", code], capture_output=True, text=True, timeout=60, check=False
    )


@pytest.mark.parametrize("module", STANDALONE_MODULES)
def test_each_module_imports_in_a_fresh_interpreter(module) -> None:
    result = run_python(f"import {module}")

    assert result.returncode == 0, result.stderr[-800:]


def test_agents_and_rules_load_without_loading_supervisor() -> None:
    result = run_python(
        "import sys\n"
        "import hops_ai.agents.registry, hops_ai.agents.inquiry.readiness\n"
        "import hops_ai.rules.progress, hops_ai.rules.proposals\n"
        "loaded = sorted(m for m in sys.modules if m.startswith('hops_ai.supervisor'))\n"
        "assert not loaded, loaded"
    )

    assert result.returncode == 0, result.stderr[-800:]


def imported_modules(path: Path) -> set[str]:
    modules: set[str] = set()
    for node in ast.walk(ast.parse(path.read_text())):
        if isinstance(node, ast.Import):
            modules.update(alias.name for alias in node.names)
        elif isinstance(node, ast.ImportFrom) and node.module and node.level == 0:
            modules.add(node.module)
    return modules


@pytest.mark.parametrize("package", NEUTRAL_PACKAGES)
def test_neutral_packages_never_import_supervisor_even_inside_functions(package) -> None:
    offenders = [
        f"{path.relative_to(SOURCE)}: {module}"
        for path in sorted((SOURCE / package).rglob("*.py"))
        for module in imported_modules(path)
        if module == "hops_ai.supervisor" or module.startswith("hops_ai.supervisor.")
    ]

    assert offenders == []
