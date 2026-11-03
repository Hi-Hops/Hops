"""Verify that only well-formed inputs reach the fixed SSM document."""

import importlib.util
import pathlib
import unittest


PATH = pathlib.Path(__file__).resolve().parents[1] / "send_ssm.py"
SPEC = importlib.util.spec_from_file_location("send_ssm", PATH)
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)


class SendSsmTest(unittest.TestCase):
    def test_source_and_images_are_pinned(self):
        commit = "a" * 40
        registry = "123456789012.dkr.ecr.ap-northeast-2.amazonaws.com"
        images = [f"{registry}/hops-{name}@sha256:{'d' * 64}" for name in ("spring", "ai", "frontend")]
        arguments = [commit, "b" * 40, "c" * 40, *images, "example.test", "1-1"]
        parameters = MODULE.make_parameters("Hi-Hops/Hops", arguments, "d" * 64)
        self.assertEqual(parameters["Commit"], [commit])
        self.assertEqual(parameters["ScriptSha256"], ["d" * 64])
        self.assertEqual(parameters["WebImage"], [images[2]])
        self.assertNotIn("commands", parameters)

    def test_rejects_untrusted_repository_or_invalid_commit(self):
        with self.assertRaises(ValueError):
            MODULE.make_parameters("Hi-Hops/Hops;echo injected", ["a" * 40] * 8, "d" * 64)
        with self.assertRaises(ValueError):
            MODULE.make_parameters("Hi-Hops/Hops", ["not-a-commit"] * 8, "d" * 64)
        with self.assertRaises(ValueError):
            MODULE.make_parameters("Hi-Hops/Hops", ["a" * 40] * 3 + ["bad image"] * 3 + ["host", "1-1"], "d" * 64)


if __name__ == "__main__":
    unittest.main()
