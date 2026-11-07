"""저장된 Curator 실행 기록을 현재 검사기로 다시 판정한다. 모델을 부르지 않는다.

    cd ai
    uv run python -m evaluation.curator.recheck [--behavior] <trace 파일 또는 디렉터리>...

검사기를 고친 뒤 이전 실행을 다시 판정하는 데 쓴다. 안전 성질 위반이 있으면 종료 코드가 1이다.
`--behavior`는 현재 정답 파일로 행동 성질(CB)의 시나리오별 통과 수도 다시 센다. 정답을 고친 경우에
모델을 다시 부르지 않고 같은 기록을 다시 세는 용도이며, 이 값은 종료 코드에 영향을 주지 않는다.
"""

import sys
from pathlib import Path

from evaluation.curator.candidates import load_catalog
from evaluation.curator.checks import check_behavior, check_trace
from evaluation.curator.loader import load_all
from evaluation.curator.trace_io import load_trace


def recheck(paths: list[Path]) -> dict[str, list[str]]:
    """파일 또는 디렉터리(`*.trace.json`)의 기록을 판정해 파일마다 위반 문장을 돌려준다."""
    files: list[Path] = []
    for path in paths:
        files.extend(sorted(path.glob("*.trace.json")) if path.is_dir() else [path])
    return {
        file.name: [f"{v.property_id}: {v.message}" for v in check_trace(load_trace(file))]
        for file in files
    }


def _files(paths: list[Path]) -> list[Path]:
    files: list[Path] = []
    for path in paths:
        files.extend(sorted(path.glob("*.trace.json")) if path.is_dir() else [path])
    return files


def rejudge_behavior(paths: list[Path]) -> dict[str, dict[str, dict[str, int]]]:
    """시나리오 ID → 성질 → {passed, total}. 결과가 없는 기록은 세지 않는다."""
    golds = {scenario.scenario_id: gold for scenario, gold in load_all()}
    keys = {key: node.id for key, node in load_catalog().items()}
    counts: dict[str, dict[str, dict[str, int]]] = {}
    for file in _files(paths):
        trace = load_trace(file)
        _, passed = check_behavior(trace, golds[trace.scenario_id], keys)
        for name, ok in passed.items():
            entry = counts.setdefault(trace.scenario_id, {}).setdefault(
                name, {"passed": 0, "total": 0}
            )
            entry["total"] += 1
            entry["passed"] += 1 if ok else 0
    return counts


def main(argv: list[str] | None = None) -> int:
    raw = list(argv if argv is not None else sys.argv[1:])
    behavior = "--behavior" in raw
    args = [Path(item) for item in raw if item != "--behavior"]
    if not args:
        print(
            "사용법: python -m evaluation.curator.recheck <trace 파일 또는 디렉터리>...",
            file=sys.stderr,
        )
        return 2
    results = recheck(args)
    for name, violations in results.items():
        print(f"{name}: 위반 {len(violations)}건")
        for violation in violations:
            print(f"  - {violation}")
    total = sum(len(v) for v in results.values())
    print(f"판정 {len(results)}건, 위반 {total}건")
    if behavior:
        for scenario_id, properties in sorted(rejudge_behavior(args).items()):
            line = ", ".join(
                f"{k} {v['passed']}/{v['total']}" for k, v in sorted(properties.items())
            )
            print(f"행동 {scenario_id}: {line}")
    return 1 if total else 0


if __name__ == "__main__":
    sys.exit(main())
