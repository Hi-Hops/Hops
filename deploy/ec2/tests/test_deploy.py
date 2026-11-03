"""Exercise deployment failure paths without touching a real Docker daemon."""

import json
import os
import pathlib
import subprocess
import tempfile
import unittest


SCRIPT = pathlib.Path(__file__).resolve().parents[1] / "deploy.sh"
COMMIT = "a" * 40
SPRING_TREE = "b" * 40
AI_TREE = "c" * 40
REGISTRY = "123456789012.dkr.ecr.ap-northeast-2.amazonaws.com"
IMAGES = [f"{REGISTRY}/hops-{name}@sha256:{'d' * 64}" for name in ("spring", "ai", "frontend")]

DOCKER = r'''#!/usr/bin/env python3
import json, os, pathlib, sys
p = pathlib.Path(os.environ['FAKE_DOCKER_STATE'])
s = json.loads(p.read_text())
a = sys.argv[1:]
s['calls'].append(a)
containers = s['containers']
result = 0
if a[:2] == ['container', 'inspect']:
    result = 0 if a[2] in containers else 1
elif a[0] == 'inspect':
    name = a[-1]
    if name not in containers:
        result = 1
    else:
        print('true' if containers[name]['running'] else 'false')
elif a[0] == 'stop':
    containers[a[1]]['running'] = False
elif a[0] == 'rename':
    containers[a[2]] = containers.pop(a[1])
elif a[0] == 'run':
    name = a[a.index('--name') + 1]
    containers[name] = {'running': True, 'version': 'new'}
elif a[0] == 'rm':
    containers.pop(a[-1], None)
elif a[0] == 'start':
    containers[a[1]]['running'] = True
elif a[0] == 'login':
    sys.stdin.read()
elif a[0] in ('pull', 'exec'):
    pass
else:
    raise AssertionError(a)
p.write_text(json.dumps(s))
sys.exit(result)
'''

CURL = r'''#!/usr/bin/env python3
import os, sys
url = sys.argv[-1]
if '8000/healthz' in url and os.environ.get('FAKE_AI_FAILURE'):
    print('503', end='')
elif url.endswith('/api/v2/auth/me'):
    print('401', end='')
else:
    print('200', end='')
'''


class DeploymentTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.root = pathlib.Path(self.tmp.name)
        self.home = self.root / "home"
        self.state = self.root / "state"
        self.bin = self.root / "bin"
        self.state.mkdir()
        self.bin.mkdir()
        (self.home / ".config/hops").mkdir(parents=True)
        (self.home / "hops-deploy/nginx").mkdir(parents=True)
        (self.home / ".config/hops/spring.env").write_text(
            "HOPS_CORS_ALLOWEDORIGINS=https://example.test\n"
        )
        (self.home / ".config/hops/ai.env").touch()
        (self.home / "rds-global-bundle.pem").touch()
        (self.home / "hops-deploy/nginx/https.conf").touch()
        (self.state / "spring-migrations.tree").write_text(SPRING_TREE + "\n")
        (self.state / "ai-migrations.tree").write_text(AI_TREE + "\n")
        self.docker_state = self.root / "docker.json"
        self.docker_state.write_text(json.dumps({
            "calls": [],
            "containers": {
                name: {"running": True, "version": "old"}
                for name in ("hops-spring", "hops-ai", "hops-nginx", "hops-redis")
            },
        }))
        for name, content in {
            "docker": DOCKER,
            "curl": CURL,
            "aws": "#!/bin/sh\nprintf 'test-token'\n",
            "flock": "#!/bin/sh\nexit 0\n",
            "sleep": "#!/bin/sh\nexit 0\n",
        }.items():
            path = self.bin / name
            path.write_text(content)
            path.chmod(0o755)
        self.env = os.environ | {
            "PATH": f"{self.bin}:{os.environ['PATH']}",
            "HOPS_DEPLOY_HOME": str(self.home),
            "HOPS_DEPLOY_STATE_DIR": str(self.state),
            "FAKE_DOCKER_STATE": str(self.docker_state),
        }

    def tearDown(self):
        self.tmp.cleanup()

    def deploy(self, **extra_env):
        return subprocess.run(
            ["bash", str(SCRIPT), COMMIT, SPRING_TREE, AI_TREE, *IMAGES, "example.test", "123-1"],
            env=self.env | extra_env, text=True, capture_output=True, check=False,
        )

    def state_after(self):
        return json.loads(self.docker_state.read_text())

    def test_success_replaces_three_services_and_keeps_redis(self):
        result = self.deploy()
        self.assertEqual(result.returncode, 0, result.stderr)
        state = self.state_after()["containers"]
        self.assertEqual(set(state), {"hops-spring", "hops-ai", "hops-nginx", "hops-redis"})
        self.assertEqual(state["hops-nginx"]["version"], "new")
        self.assertEqual(state["hops-redis"]["version"], "old")
        self.assertEqual((self.state / "deployed-commit").read_text().strip(), COMMIT)

    def test_failed_ai_health_restores_every_original_container(self):
        result = self.deploy(FAKE_AI_FAILURE="1")
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("restoring the previous containers", result.stderr)
        state = self.state_after()["containers"]
        self.assertEqual(set(state), {"hops-spring", "hops-ai", "hops-nginx", "hops-redis"})
        self.assertTrue(all(container["running"] and container["version"] == "old" for container in state.values()))
        self.assertFalse((self.state / "deployed-commit").exists())

    def test_changed_migration_stops_before_touching_containers(self):
        (self.state / "ai-migrations.tree").write_text("f" * 40 + "\n")
        result = self.deploy()
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("AI migrations changed", result.stderr)
        self.assertEqual(self.state_after()["calls"], [])


if __name__ == "__main__":
    unittest.main()
