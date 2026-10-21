"""MVP 계약 초안과 가상 예시의 형식·거부 경계를 검사한다. 외부 API를 호출하지 않는다."""

import copy
import json
import unittest
from pathlib import Path

from jsonschema import Draft202012Validator, FormatChecker


ROOT = Path(__file__).resolve().parents[1] / "contracts" / "mvp"


def read_json(path):
    return json.loads(path.read_text(encoding="utf-8"))


class MvpContractTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.request = read_json(ROOT / "fixtures/run-request.json")
        cls.result = read_json(ROOT / "fixtures/mixed-result.json")
        cls.validators = {}
        for name in ("run-request", "analysis-result"):
            schema = read_json(ROOT / f"schemas/{name}.schema.json")
            Draft202012Validator.check_schema(schema)
            cls.validators[name] = Draft202012Validator(
                schema, format_checker=FormatChecker()
            )

    def test_valid_fixtures(self):
        self.validators["run-request"].validate(self.request)
        self.validators["analysis-result"].validate(self.result)
        self.validators["analysis-result"].validate(
            read_json(ROOT / "fixtures/out-of-scope-result.json")
        )

    def test_invalid_requests(self):
        cases = []
        for key, value in (
            ("runId", "not-a-uuid"),
            ("inquiryVersion", 0),
            ("inquiryVersion", True),
            ("deadlineAt", "2026-10-21T00:00:00"),
            ("deadlineAt", "2026-10-99T00:00:00Z"),
            ("schemaVersion", "agent-1"),
            ("unknown", "extra"),
        ):
            request = copy.deepcopy(self.request)
            request[key] = value
            cases.append((key, request))
        for content in ("", " \n\t", "가" * 4001):
            request = copy.deepcopy(self.request)
            request["messages"][0]["content"] = content
            cases.append(("content", request))
        request = copy.deepcopy(self.request)
        del request["workItems"]
        cases.append(("required arrays", request))
        for name, value in cases:
            with self.subTest(name=name, value=value):
                self.assertFalse(self.validators["run-request"].is_valid(value))

    def test_user_length_boundary_and_assistant_length(self):
        request = copy.deepcopy(self.request)
        request["messages"][0]["content"] = "가" * 4000
        self.validators["run-request"].validate(request)
        request["messages"][0]["sender"] = "ASSISTANT"
        request["messages"][0]["content"] += "가"
        self.validators["run-request"].validate(request)

    def test_multiple_work_items_for_one_problem(self):
        result = copy.deepcopy(self.result)
        proposal = copy.deepcopy(result["ticketProposals"][0])
        proposal["ref"] = "new:w2"
        proposal["workScope"] = "테스트용 별도 네트워크 접근 확인"
        result["ticketProposals"].append(proposal)
        self.validators["analysis-result"].validate(result)

    def test_server_states_and_configurable_budget(self):
        request = copy.deepcopy(self.request)
        request["budget"].update(
            executionSeconds=120, modelCallSeconds=20,
            modelErrorRetries=0, formatRepairRetries=0,
        )
        problem_id = "00000000-0000-4000-8000-000000000021"
        request["problems"] = [{
            "id": problem_id, "summary": "테스트용 기존 문제",
            "category": None, "status": "FIXTURE_SERVER_PROBLEM_STATE",
            "questionCount": 3,
        }]
        request["workItems"] = [{
            "id": "00000000-0000-4000-8000-000000000022",
            "problemId": problem_id, "workScope": "테스트용 기존 작업",
            "status": "FIXTURE_SERVER_WORK_STATE", "ticketId": None,
        }]
        self.validators["run-request"].validate(request)
        for key, value in (
            ("executionSeconds", 0), ("modelCallSeconds", -1),
            ("modelErrorRetries", -1), ("formatRepairRetries", True),
            ("questionsPerProblem", 4),
        ):
            invalid = copy.deepcopy(request)
            invalid["budget"][key] = value
            with self.subTest(key=key):
                self.assertFalse(self.validators["run-request"].is_valid(invalid))

    def test_invalid_results(self):
        cases = []
        result = copy.deepcopy(self.result)
        del result["problems"][1]["question"]
        cases.append(("missing question", result))
        for key, value in (
            ("action", "RESOLVED"),
            ("ref", "model-created-id"),
            ("category", "NETWORK"),
        ):
            result = copy.deepcopy(self.result)
            result["problems"][0][key] = value
            cases.append((key, result))
        result = copy.deepcopy(self.result)
        result["ticketProposals"][0]["purpose"] = "CS_REVIEW"
        cases.append(("CS review to non-CS department", result))
        result = copy.deepcopy(self.result)
        result["ticketProposals"][0]["ticketId"] = self.request["runId"]
        cases.append(("fabricated ticket ID", result))
        for name, value in cases:
            with self.subTest(name=name):
                self.assertFalse(self.validators["analysis-result"].is_valid(value))


if __name__ == "__main__":
    unittest.main(verbosity=2)
