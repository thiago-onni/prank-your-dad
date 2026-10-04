"""Avaliação por agente (AIA-008): casos sintéticos rotulados em ``evals/<agent>/cases.jsonl``.

Formato de cada linha::

    {"id": "...", "input": {...}, "fixtures": {"regulation_requests": [...],
     "merge_cases": [...], "citizen_summaries": [...]}, "expected": {...}}

O runner executa o agente com ``InMemoryCoreClient`` carregado com os fixtures, política local,
SQLite em memória e o LLM configurado (fake por padrão) e compara com ``expected``.
"""

from __future__ import annotations

import json
from collections.abc import Callable
from dataclasses import asdict, dataclass, field
from pathlib import Path
from typing import Any

from sus_nexus_ai.config import PACKAGE_DIR, Settings
from sus_nexus_ai.llm.client import LLMClient
from sus_nexus_ai.persistence.schemas import AgentRunRecord, Trigger
from sus_nexus_ai.service import build_service
from sus_nexus_ai.tools.core_client import (
    CitizenOperationalSummary,
    InMemoryCoreClient,
    MergeCase,
    RegulationRequest,
)

DEFAULT_THRESHOLDS: dict[str, float] = {
    "regulation_completeness": 0.9,
    "mpi_duplicate_suggestion": 0.9,
    "post_discharge_followup": 0.9,
}


@dataclass
class EvalCaseResult:
    case_id: str
    passed: bool
    run_status: str
    expected: dict[str, Any]
    actual: dict[str, Any]
    mismatches: list[str] = field(default_factory=list)


@dataclass
class EvalReport:
    agent_id: str
    total: int
    passed: int
    threshold: float
    results: list[EvalCaseResult]

    @property
    def accuracy(self) -> float:
        return self.passed / self.total if self.total else 0.0

    @property
    def meets_threshold(self) -> bool:
        return self.accuracy >= self.threshold

    def to_dict(self) -> dict[str, Any]:
        return {
            "agent_id": self.agent_id,
            "total": self.total,
            "passed": self.passed,
            "accuracy": round(self.accuracy, 4),
            "threshold": self.threshold,
            "meets_threshold": self.meets_threshold,
            "results": [asdict(r) for r in self.results],
        }


def load_cases(agent_id: str, evals_dir: Path | None = None) -> list[dict[str, Any]]:
    path = (evals_dir or PACKAGE_DIR / "evals") / agent_id / "cases.jsonl"
    cases: list[dict[str, Any]] = []
    with path.open(encoding="utf-8") as fh:
        for line in fh:
            line = line.strip()
            if line and not line.startswith("#"):
                cases.append(json.loads(line))
    return cases


def _load_fixtures(core: InMemoryCoreClient, fixtures: dict[str, Any]) -> None:
    for item in fixtures.get("regulation_requests", []):
        req = RegulationRequest.model_validate(item)
        core.regulation_requests[req.id] = req
    for item in fixtures.get("merge_cases", []):
        case = MergeCase.model_validate(item)
        core.merge_cases[case.id] = case
    for item in fixtures.get("citizen_summaries", []):
        summary = CitizenOperationalSummary.model_validate(item)
        core.summaries[summary.citizen_id] = summary


Comparator = Callable[
    [AgentRunRecord, dict[str, Any], InMemoryCoreClient], tuple[dict[str, Any], list[str]]
]


def _compare_regulation(
    run: AgentRunRecord, expected: dict[str, Any], core: InMemoryCoreClient
) -> tuple[dict[str, Any], list[str]]:
    out = run.output or {}
    actual = {
        "missing_fields": sorted(out.get("missing_fields", [])),
        "pending_issue": out.get("suggested_pending_issue") is not None,
    }
    mismatches = []
    if sorted(expected.get("missing_fields", [])) != actual["missing_fields"]:
        mismatches.append("missing_fields")
    if (
        expected.get("pending_issue") is not None
        and expected["pending_issue"] != actual["pending_issue"]
    ):
        mismatches.append("pending_issue")
    return actual, mismatches


def _compare_mpi(
    run: AgentRunRecord, expected: dict[str, Any], core: InMemoryCoreClient
) -> tuple[dict[str, Any], list[str]]:
    out = run.output or {}
    actual = {"verdict": out.get("verdict"), "actions": len(run.actions)}
    mismatches = []
    if expected.get("verdict") != actual["verdict"]:
        mismatches.append("verdict")
    if actual["actions"] != 0:
        mismatches.append("actions_must_be_empty")
    return actual, mismatches


def _compare_post_discharge(
    run: AgentRunRecord, expected: dict[str, Any], core: InMemoryCoreClient
) -> tuple[dict[str, Any], list[str]]:
    out = run.output or {}
    task_created = any(a.tool == "core.create_task" and a.status == "executed" for a in run.actions)
    actual = {"risk_level": out.get("risk_level"), "task_created": task_created}
    mismatches = []
    if expected.get("risk_level") != actual["risk_level"]:
        mismatches.append("risk_level")
    if expected.get("task_created") is not None and expected["task_created"] != task_created:
        mismatches.append("task_created")
    if (
        task_created
        and expected.get("priority")
        and core.tasks[-1].priority != expected["priority"]
    ):
        mismatches.append("priority")
    return actual, mismatches


COMPARATORS: dict[str, Comparator] = {
    "regulation_completeness": _compare_regulation,
    "mpi_duplicate_suggestion": _compare_mpi,
    "post_discharge_followup": _compare_post_discharge,
}


async def run_eval(
    agent_id: str,
    *,
    evals_dir: Path | None = None,
    threshold: float | None = None,
    llm: LLMClient | None = None,
    tenant: str = "ibge_3143302",
) -> EvalReport:
    if agent_id not in COMPARATORS:
        raise KeyError(f"sem comparador de avaliação para {agent_id}")
    cases = load_cases(agent_id, evals_dir)
    compare = COMPARATORS[agent_id]
    results: list[EvalCaseResult] = []
    for case in cases:
        core = InMemoryCoreClient()
        _load_fixtures(core, case.get("fixtures", {}))
        settings = Settings(
            environment="test",
            policy_mode="local",
            identity_mode="fake",
            llm_provider="fake",
            auth_mode="mock",
            database_url="sqlite+pysqlite:///:memory:",
        )
        service = build_service(settings, core=core, llm=llm)
        run = await service.run_agent(
            agent_id,
            tenant=tenant,
            trigger=Trigger(kind="eval", ref=str(case["id"])),
            input_data=case["input"],
        )
        if run.status != "completed":
            results.append(
                EvalCaseResult(
                    case_id=str(case["id"]),
                    passed=False,
                    run_status=run.status,
                    expected=case["expected"],
                    actual={},
                    mismatches=[f"run_status:{run.status}"],
                )
            )
            continue
        actual, mismatches = compare(run, case["expected"], core)
        results.append(
            EvalCaseResult(
                case_id=str(case["id"]),
                passed=not mismatches,
                run_status=run.status,
                expected=case["expected"],
                actual=actual,
                mismatches=mismatches,
            )
        )
    return EvalReport(
        agent_id=agent_id,
        total=len(results),
        passed=sum(1 for r in results if r.passed),
        threshold=threshold if threshold is not None else DEFAULT_THRESHOLDS.get(agent_id, 0.9),
        results=results,
    )
