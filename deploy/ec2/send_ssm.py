"""Send a verified, immutable deployment command and wait for its result."""

import hashlib
import json
import os
import re
import subprocess
import tempfile
import time
from pathlib import Path


def require(pattern: str, value: str, name: str) -> str:
    if not re.fullmatch(pattern, value):
        raise ValueError(f"Invalid {name}")
    return value


def make_parameters(repository: str, arguments: list[str], script_hash: str) -> dict:
    if repository != "Hi-Hops/Hops":
        raise ValueError("Unexpected repository")
    if len(arguments) != 8:
        raise ValueError("Expected eight deployment arguments")
    for value in arguments[:3]:
        require(r"[0-9a-f]{40}", value, "commit or migration tree")
    for name, value in zip(("spring", "ai", "frontend"), arguments[3:6], strict=True):
        require(
            rf"[0-9]{{12}}\.dkr\.ecr\.[a-z0-9-]+\.amazonaws\.com/hops-{name}@sha256:[0-9a-f]{{64}}",
            value,
            "image",
        )
    require(r"[a-z0-9](?:[a-z0-9.-]*[a-z0-9])?", arguments[6], "hostname")
    require(r"[0-9]+-[0-9]+", arguments[7], "deployment ID")
    require(r"[0-9a-f]{64}", script_hash, "script hash")
    names = (
        "Commit", "SpringTree", "AiTree", "SpringImage", "AiImage", "WebImage",
        "PublicHost", "DeploymentId", "ScriptSha256",
    )
    return {name: [value] for name, value in zip(names, [*arguments, script_hash], strict=True)}


def aws(*args: str, check: bool = True) -> subprocess.CompletedProcess:
    return subprocess.run(["aws", *args], text=True, capture_output=True, check=check)


def main() -> None:
    instance = require(r"i-(?:[0-9a-f]{8}|[0-9a-f]{17})", os.environ["HOPS_EC2_INSTANCE_ID"], "instance")
    arguments = [
        os.environ[name]
        for name in (
            "GITHUB_SHA", "SPRING_MIGRATION_TREE", "AI_MIGRATION_TREE", "SPRING_IMAGE",
            "AI_IMAGE", "WEB_IMAGE", "PUBLIC_HOST", "DEPLOYMENT_ID",
        )
    ]
    script_hash = hashlib.sha256(Path("deploy/ec2/deploy.sh").read_bytes()).hexdigest()
    parameters = make_parameters(os.environ["GITHUB_REPOSITORY"], arguments, script_hash)
    with tempfile.NamedTemporaryFile(mode="w", encoding="utf-8") as parameter_file:
        json.dump(parameters, parameter_file)
        parameter_file.flush()
        sent = aws(
            "ssm", "send-command", "--document-name", "HopsDeploy",
            "--instance-ids", instance, "--parameters", f"file://{parameter_file.name}",
            "--timeout-seconds", "600", "--query", "Command.CommandId", "--output", "text",
        )
    command_id = sent.stdout.strip()
    require(r"[0-9a-f-]{36}", command_id, "SSM command ID")
    print(f"SSM command submitted: {command_id}", flush=True)

    # Allow for both delivery (600 s) and document execution (1200 s).
    deadline = time.monotonic() + 1850
    while time.monotonic() < deadline:
        result = aws(
            "ssm", "get-command-invocation", "--command-id", command_id,
            "--instance-id", instance, "--output", "json", check=False,
        )
        if result.returncode:
            if "InvocationDoesNotExist" not in result.stderr:
                raise RuntimeError(f"SSM status query failed: {result.stderr.strip()}")
        else:
            invocation = json.loads(result.stdout)
            status = invocation["Status"]
            if status in {"Success", "Failed", "Cancelled", "TimedOut"}:
                print(invocation.get("StandardOutputContent", ""), end="")
                print(invocation.get("StandardErrorContent", ""), end="")
                if status != "Success":
                    raise RuntimeError(f"EC2 deployment ended with status {status}")
                return
        time.sleep(10)
    raise TimeoutError("SSM result did not finish; inspect the command in AWS before retrying")


if __name__ == "__main__":
    main()
