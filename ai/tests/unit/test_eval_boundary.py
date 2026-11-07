"""평가 하네스와 정답 파일이 AI 런타임·실행 이미지에 들어가지 않는지 확인한다."""

import re
from pathlib import Path

AI_ROOT = Path(__file__).resolve().parents[2]


def test_runtime_package_never_imports_or_reads_the_evaluation_package() -> None:
    importing = re.compile(r"^\s*(from|import)\s+evaluation\b", re.MULTILINE)
    referring = re.compile(
        r"evaluation[/\\](golds|scenarios|harness|curator|curator_golds|curator_scenarios|flywheel)"
    )
    offenders = []
    for path in (AI_ROOT / "src").rglob("*.py"):
        text = path.read_text(encoding="utf-8")
        if importing.search(text) or referring.search(text):
            offenders.append(str(path.relative_to(AI_ROOT)))

    assert offenders == []


def test_docker_image_copies_only_the_runtime_source() -> None:
    dockerfile = (AI_ROOT / "Dockerfile").read_text(encoding="utf-8")
    ignore = [
        line.strip()
        for line in (AI_ROOT / "Dockerfile.dockerignore").read_text(encoding="utf-8").splitlines()
        if line.strip() and not line.startswith("#")
    ]

    assert "evaluation" not in dockerfile
    assert ignore[0] == "**"  # 허용 목록 방식: 명시한 것만 이미지 문맥에 들어간다
    assert not any("evaluation" in line for line in ignore)


def test_package_build_does_not_include_the_evaluation_directory() -> None:
    pyproject = (AI_ROOT / "pyproject.toml").read_text(encoding="utf-8")

    assert "evaluation" not in pyproject
    assert (AI_ROOT / "src" / "hops_ai").is_dir()
    assert not (AI_ROOT / "src" / "evaluation").exists()
