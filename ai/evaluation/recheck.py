"""저장된 실행 기록을 현재 검사기로 다시 판정한다. 모델을 부르지 않는다.

    cd ai
    uv run python -m evaluation.recheck evaluation/baselines/traces

검사기를 고친 뒤 이전 실행을 다시 판정하는 데 쓴다. 안전 성질 위반이 있으면 종료 코드가 1이다.
"""

import sys
from pathlib import Path

from evaluation.harness.checks import check_trace
from evaluation.harness.trace_io import load_trace


def recheck(paths: list[Path]) -> dict[str, list[str]]:
    """파일 또는 디렉터리(`*.trace.json`)의 기록을 판정해 시나리오별 위반 문장을 돌려준다."""
    files: list[Path] = []
    for path in paths:
        files.extend(sorted(path.glob("*.trace.json")) if path.is_dir() else [path])
    results: dict[str, list[str]] = {}
    for file in files:
        trace = load_trace(file)
        results[trace.scenario_id] = [
            f"{v.property_id} 턴 {v.turn + 1}: {v.message}" for v in check_trace(trace)
        ]
    return results


def main(argv: list[str] | None = None) -> int:
    args = [Path(item) for item in (argv if argv is not None else sys.argv[1:])]
    if not args:
        print("사용법: python -m evaluation.recheck <trace 파일 또는 디렉터리>...", file=sys.stderr)
        return 2
    results = recheck(args)
    for scenario_id, violations in results.items():
        print(f"{scenario_id}: 위반 {len(violations)}건")
        for violation in violations:
            print(f"  - {violation}")
    total = sum(len(v) for v in results.values())
    print(f"판정 {len(results)}건, 위반 {total}건")
    return 1 if total else 0


if __name__ == "__main__":
    sys.exit(main())
