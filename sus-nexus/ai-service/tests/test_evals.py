"""AIA-008: as avaliações por agente devem ficar acima do limiar (com FakeLLM determinístico)."""

from __future__ import annotations

import pytest

from sus_nexus_ai.evaluation import DEFAULT_THRESHOLDS, load_cases, run_eval
from sus_nexus_ai.evaluation.__main__ import main


@pytest.mark.parametrize("agent_id", sorted(DEFAULT_THRESHOLDS))
async def test_eval_set_meets_threshold(agent_id: str) -> None:
    cases = load_cases(agent_id)
    assert len(cases) >= 8, "cada agente precisa de pelo menos 8 casos sintéticos"
    assert len({c["id"] for c in cases}) == len(cases)
    report = await run_eval(agent_id)
    failed = [r for r in report.results if not r.passed]
    assert report.meets_threshold, (
        f"{agent_id}: {report.accuracy:.0%} < {report.threshold:.0%}; {failed}"
    )
    assert all(r.run_status == "completed" for r in report.results)


def test_eval_cli_runs_and_reports(capsys: pytest.CaptureFixture[str]) -> None:
    assert main(["run", "mpi_duplicate_suggestion"]) == 0
    out = capsys.readouterr().out
    assert "[OK ] mpi_duplicate_suggestion" in out
    assert main(["run", "post_discharge_followup", "--threshold", "1.01"]) == 1
