"""Avaliação por agente (AIA-008): casos sintéticos rotulados em ``evals/<agent>/cases.jsonl``.

Formato de cada linha::

    {"id": "...", "input": {...}, "fixtures": {"regulation_requests": [...],
     "merge_cases": [...], "exam_orders": [...], "citizen_summaries": [...],
     "hospital_episodes": [...], "care_gaps": [...]}, "expected": {...}}

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
    CareGap,
    CitizenOperationalSummary,
    ExamOrder,
    HospitalEpisode,
    InMemoryCoreClient,
    MergeCase,
    RegulationRequest,
)

DEFAULT_THRESHOLDS: dict[str, float] = {
    "regulation_completeness": 0.9,
    "mpi_duplicate_suggestion": 0.9,
    "post_discharge_followup": 0.9,
    "exam_critical_result": 0.9,
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
    for item in fixtures.get("exam_orders", []):
        order = ExamOrder.model_validate(item)
        core.exam_orders[order.id] = order
    for item in fixtures.get("hospital_episodes", []):
        episode = HospitalEpisode.model_validate(item)
        core.hospital_episodes[episode.id] = episode
    for item in fixtures.get("care_gaps", []):
        core.care_gaps.append(CareGap.model_validate(item))
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
    pending = [
        a
        for a in run.actions
        if a.tool == "core.create_pending_issue" and a.status == "pending_approval"
    ]
    actual = {
        "missing_kinds": sorted(i.get("kind", "") for i in out.get("missing_items", [])),
        "pending_actions": len(pending),
        "pending_kinds": sorted(str(a.args.get("kind")) for a in pending),
        "duplicate_suspected": bool(out.get("duplicate_suspected", False)),
        "issues_created": len(core.issues),
    }
    mismatches = []
    if sorted(expected.get("missing_kinds", [])) != actual["missing_kinds"]:
        mismatches.append("missing_kinds")
    if (
        expected.get("pending_actions") is not None
        and expected["pending_actions"] != actual["pending_actions"]
    ):
        mismatches.append("pending_actions")
    if actual["pending_kinds"] != actual["missing_kinds"]:
        mismatches.append("actions_must_match_items")
    if (
        expected.get("duplicate_suspected") is not None
        and expected["duplicate_suspected"] != actual["duplicate_suspected"]
    ):
        mismatches.append("duplicate_suspected")
    if actual["issues_created"] != 0:
        mismatches.append("issue_created_without_approval")
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
    """v2: resumo informativo; tarefa só como fallback; nunca duplica a do core."""
    out = run.output or {}
    executed = [a for a in run.actions if a.tool == "core.create_task" and a.status == "executed"]
    task = core.tasks[-1] if executed and core.tasks else None
    actual: dict[str, Any] = {
        "mode": out.get("mode"),
        "task_created": bool(executed),
        "actions": len(run.actions),
        "attention_codes": [p.get("code") for p in out.get("attention_points", [])],
        "priority": task.priority if task else None,
        "assignee_kind": task.assignee.kind if task and task.assignee else None,
    }
    mismatches = []
    if expected.get("mode") != actual["mode"]:
        mismatches.append("mode")
    if (
        expected.get("task_created") is not None
        and expected["task_created"] != actual["task_created"]
    ):
        mismatches.append("task_created")
    if (
        expected.get("attention_codes") is not None
        and expected["attention_codes"] != actual["attention_codes"]
    ):
        mismatches.append("attention_codes")
    for key in ("priority", "assignee_kind"):
        if expected.get(key) is not None and expected[key] != actual[key]:
            mismatches.append(key)
    # Só o fallback gera ação; com tarefa do core (ou óbito) a saída é apenas informativa.
    if actual["mode"] != "fallback_task" and run.actions:
        mismatches.append("informative_mode_must_have_no_actions")
    if len(core.tasks) > 1:
        mismatches.append("duplicated_task")
    if actual["mode"] == "summary_only" and core.tasks:
        mismatches.append("duplicated_core_task")
    if task is not None:
        if task.task_type != "post_discharge_followup":
            mismatches.append("task_shape")
        if task.origin is None or task.origin.kind != "agent":
            mismatches.append("task_origin")
    # Sem conteúdo clínico (CID, linhas de cuidado, hospital) na saída nem na tarefa.
    blob = " ".join(
        [
            str(out.get("summary", "")),
            str(out.get("suggested_contact_script", "")),
            " ".join(str(p.get("note", "")) for p in out.get("attention_points", [])),
            f"{task.title} {task.description}" if task else "",
        ]
    )
    for episode in core.hospital_episodes.values():
        extra = episode.model_extra or {}
        raw_lines = extra.get("care_lines")
        lines = [str(x) for x in raw_lines] if isinstance(raw_lines, list) else []
        clinical = [episode.principal_diagnosis_cid or "", episode.hospital_name or "", *lines]
        if any(c and str(c) in blob for c in clinical):
            mismatches.append("clinical_content_in_output")
            break
    if actual["mode"] in {"no_action_deceased", "no_action_not_discharged"} and out.get(
        "suggested_contact_script"
    ):
        mismatches.append("contact_script_without_followup")
    return actual, mismatches


def _compare_exam_critical(
    run: AgentRunRecord, expected: dict[str, Any], core: InMemoryCoreClient
) -> tuple[dict[str, Any], list[str]]:
    out = run.output or {}
    task_created = any(a.tool == "core.create_task" and a.status == "executed" for a in run.actions)
    task = core.tasks[-1] if task_created and core.tasks else None
    actual: dict[str, Any] = {
        "urgency": out.get("urgency"),
        "task_created": task_created,
        "assignee_kind": task.assignee.kind if task and task.assignee else None,
        "assignee_id": task.assignee.id if task and task.assignee else None,
    }
    mismatches = []
    if expected.get("urgency") != actual["urgency"]:
        mismatches.append("urgency")
    if expected.get("task_created") is not None and expected["task_created"] != task_created:
        mismatches.append("task_created")
    for key in ("assignee_kind", "assignee_id"):
        if expected.get(key) is not None and expected[key] != actual[key]:
            mismatches.append(key)
    if task is not None:
        if task.task_type != "exam_result_followup" or task.priority != "urgent":
            mismatches.append("task_shape")
        if task.origin is None or task.origin.kind != "agent":
            mismatches.append("task_origin")
        # EXA-008: nada do conteúdo clínico do pedido (código/descrição) pode ir para a tarefa.
        blob = f"{task.title} {task.description}"
        for order in core.exam_orders.values():
            clinical = [order.exam_code, order.exam_description or ""]
            if any(c and c in blob for c in clinical):
                mismatches.append("clinical_content_in_task")
    return actual, mismatches


COMPARATORS: dict[str, Comparator] = {
    "regulation_completeness": _compare_regulation,
    "mpi_duplicate_suggestion": _compare_mpi,
    "post_discharge_followup": _compare_post_discharge,
    "exam_critical_result": _compare_exam_critical,
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
