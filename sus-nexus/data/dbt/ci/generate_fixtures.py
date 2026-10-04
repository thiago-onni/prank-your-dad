#!/usr/bin/env python3
"""Gera eventos de domínio sintéticos (envelopes válidos) para a camada bronze do dbt em CI.

Simula, para um ou mais municípios, a jornada que o barramento publicaria (contracts/events/topics.yaml):
cidadãos, agendamentos (APS e rede), regulação, exames (pedido → resultado), episódios hospitalares,
planos/lacunas de cuidado, tarefas (humanas, workflow, agente, regra) e produção (contracts/events/production).

- Reaproveita listas de nomes de unidade e códigos SIGTAP de `sus-nexus/tools/synthetic-data/generate.py`.
- Todo envelope é validado contra `contracts/events/envelope.schema.json` e o `data` contra o schema do
  tópico (quando existir). Sem CPF/CNS em claro (apenas `value_masked`/`value_hash`, como no barramento).
- Inclui reentregas (mesmo `event_id`) e eventos `replay=true` para exercitar o dedupe da staging.
- Saída: um arquivo JSONL por tópico em `--out` (`sus.aps.appointment.v1.jsonl`, ...), formato idêntico ao
  valor da mensagem Kafka. `load_bronze_duckdb.py` carrega esses arquivos no DuckDB como `bronze.events_<domínio>`.

Uso:
  python3 generate_fixtures.py --out fixtures --seed 42 --anchor now
  python3 generate_fixtures.py --anchor 2026-10-01T12:00:00-03:00 --no-production
"""

from __future__ import annotations

import argparse
import hashlib
import importlib.util
import json
import random
import sys
from collections import defaultdict
from datetime import datetime, timedelta, timezone
from pathlib import Path

HERE = Path(__file__).resolve().parent
SUS_ROOT = HERE.parents[2]  # sus-nexus/
CONTRACTS = SUS_ROOT / "contracts" / "events"
BRT = timezone(timedelta(hours=-3))
CROCKFORD = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"


def _load_synthetic_lists() -> tuple[list[str], list[tuple[str, str]]]:
    path = SUS_ROOT / "tools" / "synthetic-data" / "generate.py"
    try:
        spec = importlib.util.spec_from_file_location("sus_synthetic", path)
        mod = importlib.util.module_from_spec(spec)  # type: ignore[arg-type]
        spec.loader.exec_module(mod)  # type: ignore[union-attr]
        return list(mod.UNIT_NAMES), list(mod.SIGTAP)
    except Exception:  # pragma: no cover - fallback se o gerador base mudar
        return ["UBS Centro", "UBS Vila Nova", "ESF Jardim América", "UBS Santa Rita"], [("0301010072", "CONSULTA MEDICA EM ATENCAO BASICA")]


UNIT_NAMES, SIGTAP = _load_synthetic_lists()
CARE_LINES = ["hipertensao", "diabetes", "gestante", "saude_mental", "rastreamento_cancer_mama"]
SPECIALTIES = ["cardiologia", "ortopedia", "oftalmologia", "neurologia", "endocrinologia", "dermatologia"]
EXAMS = [("0202010503", "laboratory"), ("0202010473", "laboratory"), ("0204030153", "imaging"), ("0205020038", "imaging"), ("0211020036", "other")]

# domínio de evento → arquivo de schema do `data`
DATA_SCHEMAS = {
    "sus.identity.citizen": "identity/citizen.v1.schema.json",
    "sus.aps.appointment": "schedule/appointment.v1.schema.json",
    "sus.schedule.appointment": "schedule/appointment.v1.schema.json",
    "sus.regulation.request": "regulation/request.v1.schema.json",
    "sus.regulation.status": "regulation/status.v1.schema.json",
    "sus.exam.order": "exam/order.v1.schema.json",
    "sus.exam.result": "exam/result.v1.schema.json",
    "sus.hospital.adt": "hospital/adt.v1.schema.json",
    "sus.hospital.discharge": "hospital/discharge.v1.schema.json",
    "sus.careplan": "careplan/careplan.v1.schema.json",
    "sus.caregap": "caregap/caregap.v1.schema.json",
    "sus.task": "task/task.v1.schema.json",
    "sus.production.record": "production/record.v1.schema.json",
    "sus.production.validation": "production/validation.v1.schema.json",
    "sus.production.submission": "production/submission.v1.schema.json",
    "sus.production.outcome": "production/outcome.v1.schema.json",
}


class Gen:
    def __init__(self, seed: int, anchor: datetime, days: int, with_production: bool) -> None:
        self.rng = random.Random(seed)
        self.anchor = anchor
        self.start = anchor - timedelta(days=days)
        self.with_production = with_production
        self.out: dict[str, list[dict]] = defaultdict(list)
        self.ulid_seq = 0

    # ------------------------------------------------------------------ utilidades
    def ulid(self, prefix: str, at: datetime | None = None) -> str:
        at = at or self.anchor
        ms = int(at.timestamp() * 1000)
        t = "".join(CROCKFORD[(ms >> (5 * i)) & 31] for i in reversed(range(10)))
        self.ulid_seq += 1
        r = "".join(self.rng.choice(CROCKFORD) for _ in range(16))
        return f"{prefix}_{t}{r}"

    def rand_dt(self, a: datetime, b: datetime) -> datetime:
        if b <= a:
            return a
        return a + timedelta(seconds=self.rng.randrange(0, int((b - a).total_seconds())))

    def past(self, dt: datetime) -> bool:
        return dt <= self.anchor

    @staticmethod
    def iso(dt: datetime) -> str:
        return dt.astimezone(BRT).isoformat(timespec="seconds")

    def emit(self, topic: str, event_type: str, occurred: datetime, tenant: str, data: dict,
             citizen: dict | None = None, source: tuple[str, str] = ("SUS_NEXUS_CORE", "core-municipal"),
             cnes: str | None = None, purpose: list[str] | None = None) -> None:
        if not self.past(occurred):
            return
        env: dict = {
            "event_id": self.ulid("evt", occurred),
            "event_type": event_type,
            "event_version": "1.0",
            "occurred_at": self.iso(occurred),
            "published_at": self.iso(min(occurred + timedelta(seconds=self.rng.randrange(1, 90)), self.anchor)),
            "tenant": {"municipality_id": tenant, "health_secretariat_id": "sms_" + tenant[-7:]},
            "source": {"system": source[0], "connector": source[1], "source_record_id": f"SRC-{self.ulid_seq:08d}", "source_record_version": "1"},
            "data": data,
            "privacy": {"classification": "restricted", "purpose": purpose or ["care_coordination", "management_analytics"]},
            "trace": {"correlation_id": f"corr_{self.rng.getrandbits(40):010x}", "schema_version": "1.0.0"},
            "replay": False,
        }
        if cnes:
            env["source"]["cnes"] = cnes
        if citizen:
            env["subject"] = {
                "municipal_citizen_id": citizen["id"],
                "identifiers": [{"system": "CNS", "value_masked": "***********" + citizen["cns_tail"], "value_hash": citizen["cns_hash"]}],
            }
        self.out[topic].append(env)
        r = self.rng.random()
        if r < 0.02:  # reentrega (at-least-once): mesmo event_id
            self.out[topic].append(json.loads(json.dumps(env)))
        elif r < 0.03:  # replay histórico: mesmo event_id, replay=true
            rep = json.loads(json.dumps(env))
            rep["replay"] = True
            rep["published_at"] = self.iso(self.anchor)
            self.out[topic].append(rep)

    # ------------------------------------------------------------------ cenário
    def build_tenant(self, tenant: str, n_citizens: int, n_units: int) -> None:
        rng = self.rng
        ubs = []
        for i in range(n_units):
            cnes = f"{rng.randrange(2000000, 2999999)}"
            teams = [{"ine": f"{rng.randrange(1000000000, 9999999999)}", "microareas": [f"{m:02d}" for m in range(1, 5)]} for _ in range(2)]
            ubs.append({"cnes": cnes, "name": UNIT_NAMES[i % len(UNIT_NAMES)], "teams": teams})
        hospitals = [f"{rng.randrange(3000000, 3999999)}" for _ in range(2)]
        providers = [f"{rng.randrange(4000000, 4999999)}" for _ in range(3)]
        self.units = getattr(self, "units", []) + [{"tenant": tenant, "cnes": u["cnes"], "name": u["name"], "kind": "ubs"} for u in ubs]

        citizens = []
        for _ in range(n_citizens):
            u = rng.choice(ubs)
            t = rng.choice(u["teams"])
            created = self.rand_dt(self.start - timedelta(days=30), self.anchor - timedelta(days=20))
            cid = self.ulid("cit", created)
            c = {"id": cid, "cnes": u["cnes"], "ine": t["ine"], "micro": rng.choice(t["microareas"]),
                 "cns_tail": f"{rng.randrange(0, 9999):04d}", "cns_hash": hashlib.sha256(cid.encode()).hexdigest()}
            citizens.append(c)
            territory = {"health_unit_cnes": c["cnes"], "team_ine": c["ine"], "microarea": c["micro"]}
            self.emit("sus.identity.citizen.v1", "sus.identity.citizen.created", created, tenant, {
                "action": "created", "municipal_citizen_id": cid, "registration_state": rng.choice(["validated"] * 8 + ["incomplete", "pending"]),
                "match": {"method": "deterministic_cns", "classification": "new", "rule_version": "mpi-rules-1.0"},
                "linked_identifier_systems": ["CNS", "PEC"], "territory": territory,
            }, citizen=c, source=("ESUS_APS_PEC", "connector-pec"), cnes=c["cnes"], purpose=["identity_management"])
            if rng.random() < 0.1:  # mudança de território
                u2 = rng.choice(ubs)
                t2 = rng.choice(u2["teams"])
                c.update(cnes=u2["cnes"], ine=t2["ine"], micro=rng.choice(t2["microareas"]))
                self.emit("sus.identity.citizen.v1", "sus.identity.citizen.updated", created + timedelta(days=rng.randrange(1, 15)), tenant, {
                    "action": "updated", "municipal_citizen_id": cid, "registration_state": "validated", "changed_attributes": ["territory"],
                    "territory": {"health_unit_cnes": c["cnes"], "team_ine": c["ine"], "microarea": c["micro"]},
                }, citizen=c, purpose=["identity_management"])

        tasks_for_later: list[tuple] = []
        self._appointments(tenant, citizens, ubs, providers)
        self._regulation(tenant, citizens, providers)
        self._exams(tenant, citizens, providers)
        self._hospital(tenant, citizens, hospitals, tasks_for_later)
        self._care(tenant, citizens, tasks_for_later)
        self._tasks(tenant, citizens, tasks_for_later)
        if self.with_production:
            self._production(tenant, ubs)

    def _appt_events(self, tenant: str, c: dict, cnes: str, kind: str, start: datetime, created: datetime,
                     outcome: str, extra: dict | None = None) -> dict:
        rng = self.rng
        topic = "sus.aps.appointment.v1" if kind in ("direct", "walk_in") else "sus.schedule.appointment.v1"
        prefix = topic[:-3]
        apt_id = self.ulid("apt", created)
        cancel_at = None
        svc = rng.choice(SIGTAP[:2]) if topic.startswith("sus.aps") else rng.choice(SIGTAP)
        base = {"appointment_id": apt_id, "kind": kind, "service_code": svc[0], "code_system": "SIGTAP", "health_unit_cnes": cnes,
                "professional_id": f"prof_{rng.randrange(1, 40):03d}", "scheduled_start": self.iso(start), "scheduled_end": self.iso(start + timedelta(minutes=20))}
        if extra:
            base.update(extra)
        src = ("AGENDA_MUNICIPAL", "connector-agenda")
        self.emit(topic, f"{prefix}.created", created, tenant, {**base, "action": "created", "status": "booked"}, citizen=c, source=src, cnes=cnes, purpose=["scheduling"])
        if rng.random() < 0.6:
            self.emit(topic, f"{prefix}.confirmed", self.rand_dt(created, start - timedelta(hours=2)), tenant,
                      {**base, "action": "confirmed", "status": "confirmed", "previous_status": "booked"}, citizen=c, source=src, cnes=cnes, purpose=["scheduling"])
        if outcome == "attended":
            self.emit(topic, f"{prefix}.attended", start + timedelta(minutes=rng.randrange(0, 40)), tenant,
                      {**base, "action": "attended", "status": "fulfilled", "previous_status": "confirmed"}, citizen=c, source=src, cnes=cnes, purpose=["scheduling"])
        elif outcome == "no_show":
            self.emit(topic, f"{prefix}.no_show", start + timedelta(hours=2), tenant,
                      {**base, "action": "no_show", "status": "noshow", "previous_status": "booked"}, citizen=c, source=src, cnes=cnes, purpose=["scheduling"])
        elif outcome == "cancelled":
            cancel_at = self.rand_dt(created, start - timedelta(hours=6))
            self.emit(topic, f"{prefix}.cancelled", cancel_at, tenant,
                      {**base, "action": "cancelled", "status": "cancelled", "previous_status": "booked",
                       "cancellation_reason": rng.choice(["patient_request", "professional_absence", "duplicate"])}, citizen=c, source=src, cnes=cnes, purpose=["scheduling"])
        return {"id": apt_id, "cnes": cnes, "start": start, "base": base, "topic": topic, "cancel_at": cancel_at}

    def _appointments(self, tenant: str, citizens: list[dict], ubs: list[dict], providers: list[str]) -> None:
        rng = self.rng
        for _ in range(int(len(citizens) * 2.2)):
            c = rng.choice(citizens)
            kind = rng.choice(["direct"] * 6 + ["walk_in", "regulated", "return"])
            cnes = c["cnes"] if kind in ("direct", "walk_in", "return") else rng.choice(providers)
            start = self.rand_dt(self.start, self.anchor + timedelta(days=30)).replace(minute=0, second=0)
            start = start.replace(hour=rng.randrange(7, 17))
            created = start - timedelta(days=rng.randrange(1, 25))
            if not self.past(created):
                continue
            r = rng.random()
            outcome = "attended" if r < 0.70 else "no_show" if r < 0.84 else "cancelled"
            if start > self.anchor:
                outcome = "cancelled" if r > 0.92 else "pending"
            a = self._appt_events(tenant, c, cnes, kind, start, created, outcome)
            if outcome == "cancelled" and rng.random() < 0.5:  # reaproveitamento da vaga (AGE-010)
                c2 = rng.choice(citizens)
                re_created = a["cancel_at"] + timedelta(hours=self.rng.randrange(1, 12))
                if re_created < start - timedelta(hours=1):
                    self._appt_events(tenant, c2, cnes, kind, start, re_created,
                                      "attended" if start <= self.anchor else "pending", {"service_code": a["base"]["service_code"]})

    def _regulation(self, tenant: str, citizens: list[dict], providers: list[str]) -> None:
        rng = self.rng
        sla_days = {"emergency": 1, "urgent": 3, "priority": 15, "elective": 60}
        for _ in range(int(len(citizens) * 0.6)):
            c = rng.choice(citizens)
            requested = self.rand_dt(self.start, self.anchor - timedelta(days=1))
            reg_id = self.ulid("reg", requested)
            prio = rng.choice(["elective"] * 5 + ["priority"] * 3 + ["urgent", "emergency"])
            kind = rng.choice(["consultation"] * 3 + ["exam", "procedure", "surgery"])
            base = {"regulation_request_id": reg_id, "kind": kind, "priority": prio, "requested_service_code": rng.choice(SIGTAP)[0],
                    "code_system": "SIGTAP", "specialty": rng.choice(SPECIALTIES), "requested_at": self.iso(requested),
                    "requesting_cnes": c["cnes"], "sla_due_at": self.iso(requested + timedelta(days=sla_days[prio]))}
            src = ("SISREG", "connector-sisreg")
            self.emit("sus.regulation.request.v1", "sus.regulation.request.created", requested, tenant,
                      {**base, "action": "created", "status": "requested", "justification_present": rng.random() > 0.1,
                       "attached_documents_count": rng.randrange(0, 4)}, citizen=c, source=src, purpose=["regulation"])
            t = requested
            status = "requested"

            def change(new: str, at: datetime, **kw) -> None:
                nonlocal status
                d = {"action": "changed", "regulation_request_id": reg_id, "status": new, "previous_status": status, "priority": prio,
                     "occurred_at": self.iso(at), "actor_kind": kw.pop("actor_kind", "regulator")}
                d.update(kw)
                self.emit("sus.regulation.status.v1", "sus.regulation.status.changed", at, tenant, d, citizen=c, source=src, purpose=["regulation"])
                status = new

            scale = sla_days[prio]
            t += timedelta(hours=rng.randrange(2, 24 * max(1, scale // 4)))
            change("under_review", t)
            if rng.random() < 0.18:  # devolução por documentação
                t += timedelta(hours=rng.randrange(4, 72))
                change("returned", t, return_to_origin=True, reason="documentacao_incompleta")
                self.emit("sus.regulation.request.v1", "sus.regulation.request.returned", t, tenant,
                          {**base, "action": "returned", "status": "returned", "reason": "documentacao_incompleta"}, citizen=c, source=src, purpose=["regulation"])
                t += timedelta(days=rng.randrange(1, 10))
                change("under_review", t, actor_kind="requester")
            r = rng.random()
            if r < 0.08:
                t += timedelta(days=rng.randrange(1, 5))
                change("denied", t, reason="criterio_nao_atendido")
                continue
            if r < 0.13:
                t += timedelta(days=rng.randrange(1, 20))
                change("cancelled", t, actor_kind="requester")
                continue
            t += timedelta(days=rng.random() * scale * 1.3)
            prov = rng.choice(providers)
            change("authorized", t, provider_cnes=prov)
            t += timedelta(days=rng.random() * scale * 0.5)
            sched = t + timedelta(days=rng.randrange(3, 30))
            change("scheduled", t, provider_cnes=prov, scheduled_at=self.iso(sched), sla_breached=t > requested + timedelta(days=scale))
            if rng.random() < 0.85:
                change("performed", sched, provider_cnes=prov, actor_kind="provider")
            else:
                change("no_show", sched + timedelta(hours=3), provider_cnes=prov, actor_kind="provider")

    def _exams(self, tenant: str, citizens: list[dict], providers: list[str]) -> None:
        rng = self.rng
        for _ in range(int(len(citizens) * 0.8)):
            c = rng.choice(citizens)
            requested = self.rand_dt(self.start, self.anchor - timedelta(hours=6))
            exo = self.ulid("exo", requested)
            code, cat = rng.choice(EXAMS)
            perf = rng.choice(providers)
            base = {"exam_order_id": exo, "exam_code": code, "code_system": "SIGTAP", "category": cat,
                    "priority": rng.choice(["routine"] * 6 + ["priority", "urgent"]), "requested_at": self.iso(requested),
                    "requesting_cnes": c["cnes"], "care_line": rng.choice(CARE_LINES)}
            src = ("LIS", "connector-lis") if cat == "laboratory" else ("RIS", "connector-ris")
            self.emit("sus.exam.order.v1", "sus.exam.order.created", requested, tenant, {**base, "action": "created", "status": "requested"}, citizen=c, source=src)
            t = requested
            prev = "requested"
            steps = ["authorized", "scheduled", "collected" if cat == "laboratory" else "performed", "reported"]
            stop_at = rng.choices([1, 2, 3, 4, 4, 4, 4], k=1)[0]
            sched_at = None
            for i, st in enumerate(steps[:stop_at]):
                t += timedelta(hours=rng.randrange(4, 24 * (6 if st == "scheduled" else 3)))
                d = {**base, "action": "status_changed", "status": st, "previous_status": prev, "performer_cnes": perf}
                if st == "scheduled":
                    sched_at = t + timedelta(days=rng.randrange(1, 15))
                    d["scheduled_at"] = self.iso(sched_at)
                if st in ("collected", "performed") and sched_at:
                    t = max(t, sched_at)
                self.emit("sus.exam.order.v1", "sus.exam.order.status_changed", t, tenant, d, citizen=c, source=src)
                prev = st
                if st == "reported":
                    exr = self.ulid("exr", t)
                    crit = rng.random() < 0.05
                    self.emit("sus.exam.result.v1", "sus.exam.result.available", t, tenant, {
                        "action": "available", "exam_order_id": exo, "exam_result_id": exr, "result_status": "final", "critical": crit,
                        "reported_at": self.iso(t), "performer_cnes": perf, "has_document": True, "requesting_cnes": c["cnes"],
                        "care_line": base["care_line"]}, citizen=c, source=src)
                    if crit:
                        self.emit("sus.exam.result.v1", "sus.exam.result.critical_flagged", t + timedelta(minutes=5), tenant, {
                            "action": "critical_flagged", "exam_order_id": exo, "exam_result_id": exr, "result_status": "final", "critical": True,
                            "reported_at": self.iso(t)}, citizen=c, source=src)
                    if rng.random() < 0.6:  # retorno com resultado
                        rs = t + timedelta(days=rng.randrange(2, 25))
                        self._appt_events(tenant, c, c["cnes"], "return", rs.replace(hour=rng.randrange(7, 17), minute=0, second=0),
                                          t + timedelta(days=1), "attended" if rng.random() < 0.85 else "no_show", {"exam_order_id": exo})
            if stop_at == 1 and rng.random() < 0.3:
                t += timedelta(days=rng.randrange(1, 30))
                self.emit("sus.exam.order.v1", "sus.exam.order.status_changed", t, tenant,
                          {**base, "action": "status_changed", "status": "cancelled", "previous_status": prev, "reason": "desistencia"}, citizen=c, source=src)

    def _hospital(self, tenant: str, citizens: list[dict], hospitals: list[str], later: list[tuple]) -> None:
        rng = self.rng
        cohort = rng.sample(citizens, k=max(5, int(len(citizens) * 0.25)))
        for c in cohort:
            adm = self.rand_dt(self.start, self.anchor - timedelta(days=1))
            n_eps = 2 if rng.random() < 0.2 else 1
            for _ in range(n_eps):
                hosp = rng.choice(hospitals)
                hep = self.ulid("hep", adm)
                cls = rng.choice(["inpatient"] * 4 + ["emergency", "observation"])
                src = ("HIS_HOSPITAL_MUNICIPAL", "connector-his")
                diag = {"system": "CID10", "code": rng.choice(["I10", "E11", "J18", "I50", "N39", "O80"])}
                common = {"hospital_episode_id": hep, "hospital_cnes": hosp, "episode_class": cls, "admitted_at": self.iso(adm)}
                self.emit("sus.hospital.adt.v1", "sus.hospital.adt.admitted", adm, tenant, {**common, "action": "admitted", "status": "admitted",
                          "occurred_at": self.iso(adm), "ward": "clinica", "principal_diagnosis": diag,
                          "admission_source": rng.choice(["emergency", "regulation", "elective"])}, citizen=c, source=src, cnes=hosp)
                los_h = rng.randrange(6, 24 * 12)
                if rng.random() < 0.3:
                    tr = adm + timedelta(hours=los_h // 2)
                    self.emit("sus.hospital.adt.v1", "sus.hospital.adt.transferred", tr, tenant, {**common, "action": "transferred", "status": "in_progress",
                              "occurred_at": self.iso(tr), "ward": "uti", "previous_ward": "clinica"}, citizen=c, source=src, cnes=hosp)
                dis = adm + timedelta(hours=los_h)
                if not self.past(dis):
                    break
                deceased = rng.random() < 0.03
                self.emit("sus.hospital.adt.v1", f"sus.hospital.adt.{'deceased' if deceased else 'discharged'}", dis, tenant, {**common,
                          "action": "deceased" if deceased else "discharged", "status": "deceased" if deceased else "discharged",
                          "occurred_at": self.iso(dis)}, citizen=c, source=src, cnes=hosp)
                risk = rng.choice(["low", "medium", "high"])
                self.emit("sus.hospital.discharge.v1", "sus.hospital.discharge.completed", dis + timedelta(minutes=30), tenant, {
                    "action": "completed", "hospital_episode_id": hep, "hospital_cnes": hosp, "discharged_at": self.iso(dis), "admitted_at": self.iso(adm),
                    "length_of_stay_days": los_h // 24, "disposition": "deceased" if deceased else rng.choice(["home"] * 4 + ["home_with_care", "transfer"]),
                    "principal_diagnosis": diag, "followup_plan_present": rng.random() > 0.2, "followup_due_days": 7,
                    "reference_health_unit_cnes": c["cnes"], "reference_team_ine": c["ine"], "risk_level": risk, "risk_rule_version": "pos-alta-1.2",
                    "care_lines": [rng.choice(CARE_LINES)], "counter_referral_document_present": rng.random() > 0.4}, citizen=c, source=src, cnes=hosp)
                if deceased:
                    break
                later.append(("post_discharge_followup", c, dis + timedelta(minutes=rng.randrange(2, 14)), "agent", hep))
                if rng.random() < 0.7:  # contato pós-alta na APS
                    cs = dis + timedelta(days=rng.randrange(1, 12))
                    self._appt_events(tenant, c, c["cnes"], "direct", cs.replace(hour=rng.randrange(7, 17), minute=0, second=0),
                                      dis + timedelta(hours=6), "attended" if cs <= self.anchor else "pending", {"care_line": "pos_alta"})
                adm = dis + timedelta(days=rng.randrange(3, 60))
                if not self.past(adm):
                    break

    def _care(self, tenant: str, citizens: list[dict], later: list[tuple]) -> None:
        rng = self.rng
        for c in rng.sample(citizens, k=int(len(citizens) * 0.3)):
            created = self.rand_dt(self.start, self.anchor - timedelta(days=2))
            cp = self.ulid("cp", created)
            line = rng.choice(CARE_LINES)
            base = {"care_plan_id": cp, "care_line": line, "protocol_id": f"prot_{line}", "protocol_version": "2026.1",
                    "health_unit_cnes": c["cnes"], "team_ine": c["ine"], "origin": {"kind": rng.choice(["professional", "rule", "hospital_discharge"])}}
            self.emit("sus.careplan.v1", "sus.careplan.created", created, tenant, {**base, "action": "created", "status": "active", "items_total": 6, "items_due": 2, "items_overdue": 0}, citizen=c)
            if rng.random() < 0.2:
                self.emit("sus.careplan.v1", "sus.careplan.closed", created + timedelta(days=rng.randrange(10, 90)), tenant,
                          {**base, "action": "closed", "status": "completed", "closed_reason": "metas_atingidas"}, citizen=c)
            for _ in range(rng.randrange(0, 3)):
                det = self.rand_dt(created, self.anchor)
                gap = self.ulid("gap", det)
                gk = rng.choice(["consultation_overdue", "exam_overdue", "vaccine_overdue", "return_overdue", "no_contact", "lost_to_followup"])
                g = {"care_gap_id": gap, "care_plan_id": cp, "care_line": line, "gap_kind": gk, "expected_by": self.iso(det - timedelta(days=rng.randrange(1, 60))),
                     "days_overdue": rng.randrange(1, 90), "protocol_id": base["protocol_id"], "protocol_version": "2026.1",
                     "health_unit_cnes": c["cnes"], "team_ine": c["ine"], "microarea": c["micro"], "detected_at": self.iso(det)}
                self.emit("sus.caregap.v1", "sus.caregap.detected", det, tenant, {**g, "action": "detected"}, citizen=c, source=("SUS_NEXUS_CORE", "care-rules"))
                later.append(("care_gap", c, det + timedelta(minutes=1), "rule", gap))
                if rng.random() < 0.55:
                    res = det + timedelta(days=rng.randrange(1, 45))
                    self.emit("sus.caregap.v1", "sus.caregap.resolved", res, tenant, {**g, "action": "resolved", "resolved_at": self.iso(res),
                              "resolution": rng.choice(["performed", "scheduled", "contact_made", "refused", "moved"])}, citizen=c)

    def _tasks(self, tenant: str, citizens: list[dict], later: list[tuple]) -> None:
        rng = self.rng
        for _ in range(int(len(citizens) * 0.5)):
            kind, origin = rng.choice([("no_show_recovery", "workflow"), ("active_search", "agent"), ("generic", "user"),
                                       ("regulation_pending_document", "workflow"), ("exam_result_followup", "rule"), ("mpi_review", "agent"),
                                       ("integration_error", "connector")])
            later.append((kind, rng.choice(citizens), self.rand_dt(self.start, self.anchor - timedelta(hours=1)), origin, None))
        sla_h = {"urgent": 24, "high": 72, "medium": 168, "low": 336}
        for kind, c, created, origin, ref in later:
            task = self.ulid("task", created)
            prio = "high" if kind == "post_discharge_followup" else rng.choice(["low", "medium", "medium", "high", "urgent"])
            due = created + timedelta(hours=sla_h[prio])
            base = {"task_id": task, "task_type": kind, "priority": prio, "due_at": self.iso(due), "sla_policy_id": f"sla_{prio}",
                    "origin": {"kind": origin, "id": {"agent": "agent_pos_alta", "workflow": "wf_" + kind, "rule": "rule_" + kind}.get(origin, "user"), "version": "1"}}
            self.emit("sus.task.v1", "sus.task.created", created, tenant, {**base, "action": "created", "status": "open"}, citizen=c)
            t = created + timedelta(hours=rng.randrange(1, 30))
            self.emit("sus.task.v1", "sus.task.assigned", t, tenant, {**base, "action": "assigned", "status": "assigned",
                      "assignee": {"kind": "team", "id": f"team_{c['ine']}"}}, citizen=c)
            r = rng.random()
            done = created + timedelta(hours=rng.random() * sla_h[prio] * 1.6)
            if r < 0.08:
                self.emit("sus.task.v1", "sus.task.cancelled", done, tenant, {**base, "action": "cancelled", "status": "cancelled", "reason": "duplicada"}, citizen=c)
                continue
            if r < 0.12:
                self.emit("sus.task.v1", "sus.task.escalated", due - timedelta(hours=1), tenant, {**base, "action": "escalated", "status": "escalated"}, citizen=c)
            if done > due:
                self.emit("sus.task.v1", "sus.task.sla_breached", due, tenant, {**base, "action": "sla_breached", "status": "assigned"}, citizen=c)
            if r < 0.9:
                self.emit("sus.task.v1", "sus.task.completed", done, tenant, {**base, "action": "completed", "status": "completed", "outcome": "resolvida"}, citizen=c)

    def _production(self, tenant: str, ubs: list[dict]) -> None:
        """Produção (contracts/events/production): registro → validação → lote → retorno oficial."""
        rng = self.rng
        months = sorted({(self.start + timedelta(days=d)).strftime("%Y%m") for d in range(0, (self.anchor - self.start).days, 10)})
        rules = [("SIGTAP_CBO_COMPATIVEL", "error"), ("CNS_VALIDO", "error"), ("IDADE_COMPATIVEL", "warning"), ("QUANTIDADE_MAXIMA", "warning")]
        src = ("SUS_NEXUS_CORE", "core-municipal")
        for comp in months[:-1]:
            comp_start = datetime.strptime(comp + "01", "%Y%m%d").replace(tzinfo=BRT)
            deadline = comp_start + timedelta(days=40)
            for u in ubs:
                for kind in ("bpa_c", "bpa_i"):
                    batch = self.ulid("pbat", deadline)
                    approved_qty = 0
                    n_records = 0
                    for _ in range(rng.randrange(8, 20)):
                        created = comp_start + timedelta(days=rng.randrange(0, 27), hours=rng.randrange(7, 18))
                        pid = self.ulid("prod", created)
                        qty = rng.randrange(1, 3)
                        common = {"production_record_id": pid, "kind": kind, "competence": comp, "cnes": u["cnes"],
                                  "procedure_code": rng.choice(SIGTAP)[0], "quantity": qty}
                        rec = {**common, "professional_cbo": rng.choice(["225142", "223505", "322205"]),
                               "attendance_date": created.date().isoformat(), "rule_version": "prod-rules-2026.1",
                               "estimated_value": round(qty * rng.uniform(2.0, 30.0), 2), "deadline_at": self.iso(deadline)}
                        self.emit("sus.production.record.v1", "sus.production.record.created", created, tenant,
                                  {**rec, "action": "created", "status": "generated", "errors_count": 0, "warnings_count": 0, "correction_count": 0},
                                  source=src, cnes=u["cnes"], purpose=["production_audit"])
                        vt = created + timedelta(hours=2)
                        valid = True
                        corrections = 0
                        if rng.random() < 0.2:
                            rule, sev = rng.choice(rules)
                            issue = {"production_record_id": pid, "issue_id": self.ulid("pis", vt), "rule_id": rule,
                                     "rule_version": "prod-rules-2026.1", "severity": sev, "origin": "rule", "kind": kind,
                                     "competence": comp, "cnes": u["cnes"]}
                            self.emit("sus.production.validation.v1", "sus.production.validation.issue_found", vt, tenant,
                                      {**issue, "action": "issue_found", "status": "open"}, source=src, cnes=u["cnes"], purpose=["production_audit"])
                            errs, warns = (1, 0) if sev == "error" else (0, 1)
                            if sev == "error":
                                self.emit("sus.production.record.v1", "sus.production.record.pending", vt + timedelta(minutes=1), tenant,
                                          {**rec, "action": "pending", "status": "pending", "errors_count": errs, "warnings_count": warns,
                                           "correction_count": 0}, source=src, cnes=u["cnes"], purpose=["production_audit"])
                                if rng.random() < 0.6:
                                    corrections = 1
                                    ct = vt + timedelta(days=rng.randrange(1, 5))
                                    self.emit("sus.production.validation.v1", "sus.production.validation.issue_resolved", ct, tenant,
                                              {**issue, "action": "issue_resolved", "status": "resolved"}, source=src, cnes=u["cnes"], purpose=["production_audit"])
                                    self.emit("sus.production.record.v1", "sus.production.record.corrected", ct + timedelta(minutes=5), tenant,
                                              {**rec, "action": "corrected", "status": "corrected", "errors_count": 0, "warnings_count": 0,
                                               "correction_count": 1}, source=src, cnes=u["cnes"], purpose=["production_audit"])
                                else:
                                    valid = False
                        if not valid:
                            continue
                        self.emit("sus.production.record.v1", "sus.production.record.validated", vt + timedelta(days=corrections * 5 + 1), tenant,
                                  {**rec, "action": "validated", "status": "validated", "errors_count": 0, "warnings_count": 0,
                                   "correction_count": corrections}, source=src, cnes=u["cnes"], purpose=["production_audit"])
                        n_records += 1
                        # retorno oficial (SIA): recebido → aceito/rejeitado → pago
                        rt = deadline + timedelta(days=rng.randrange(5, 15))
                        out = {"production_record_id": pid, "batch_id": batch, "competence": comp, "cnes": u["cnes"],
                               "kind": kind, "procedure_code": common["procedure_code"]}
                        self.emit("sus.production.outcome.v1", "sus.production.outcome.received", rt, tenant,
                                  {**out, "action": "received", "outcome": "received", "record_status": "received",
                                   "outcome_id": self.ulid("pout", rt), "processed_at": self.iso(rt)}, source=("SIA", "connector-sia"), purpose=["production_audit"])
                        r = rng.random()
                        at = rt + timedelta(days=3)
                        if r < 0.12:
                            self.emit("sus.production.outcome.v1", "sus.production.outcome.rejected", at, tenant,
                                      {**out, "action": "rejected", "outcome": "rejected", "record_status": "rejected", "outcome_id": self.ulid("pout", at),
                                       "reason_code": rng.choice(["0301", "0405", "0612"]), "reason": "glosa por incompatibilidade SIGTAP",
                                       "processed_at": self.iso(at)}, source=("SIA", "connector-sia"), purpose=["production_audit"])
                            continue
                        approved_qty += qty
                        self.emit("sus.production.outcome.v1", "sus.production.outcome.accepted", at, tenant,
                                  {**out, "action": "accepted", "outcome": "accepted", "record_status": "approved", "outcome_id": self.ulid("pout", at),
                                   "approved_quantity": qty, "processed_at": self.iso(at)}, source=("SIA", "connector-sia"), purpose=["production_audit"])
                        if rng.random() < 0.8:
                            pt = at + timedelta(days=20)
                            self.emit("sus.production.outcome.v1", "sus.production.outcome.paid", pt, tenant,
                                      {**out, "action": "paid", "outcome": "paid", "record_status": "paid", "outcome_id": self.ulid("pout", pt),
                                       "approved_quantity": qty, "paid_amount": rec["estimated_value"], "processed_at": self.iso(pt)},
                                      source=("SIA", "connector-sia"), purpose=["production_audit"])
                    # lote: gerado → aprovado → exportado → transmitido (no sistema oficial)
                    base = {"batch_id": batch, "competence": comp, "cnes": u["cnes"], "kind": kind, "records_count": n_records,
                            "total_quantity": max(approved_qty, 0), "layout": "BPA-MAG-2026"}
                    bt = deadline - timedelta(days=12)
                    for i, (act, st) in enumerate([("batch_generated", "draft"), ("batch_approved", "approved"), ("exported", "exported"), ("transmitted", "transmitted")]):
                        d = {**base, "action": act, "status": st}
                        if act == "batch_approved":
                            d.update(approved_by="usr_coord_producao", actor_kind="user")
                        if act == "exported":
                            d.update(file_sha256=hashlib.sha256(batch.encode()).hexdigest(), file_lines=n_records + 2, actor_kind="service")
                        if act == "transmitted":
                            d.update(protocol_number=f"SIA-{comp}-{u['cnes']}-{kind}", actor_kind="official_system")
                        self.emit("sus.production.submission.v1", f"sus.production.submission.{act}", bt + timedelta(days=i * 2), tenant, d,
                                  source=src, cnes=u["cnes"], purpose=["production_audit"])


def validate(out: dict[str, list[dict]]) -> int:
    try:
        from jsonschema import Draft202012Validator
    except ImportError:
        print("!! jsonschema não instalado — validação de contratos ignorada", file=sys.stderr)
        return 0
    env_v = Draft202012Validator(json.loads((CONTRACTS / "envelope.schema.json").read_text()))
    data_v = {k: Draft202012Validator(json.loads((CONTRACTS / p).read_text())) for k, p in DATA_SCHEMAS.items() if (CONTRACTS / p).exists()}
    errors = 0
    for topic, events in out.items():
        for e in events:
            for err in env_v.iter_errors(e):
                errors += 1
                if errors < 20:
                    print(f"envelope {topic} {e['event_id']}: {err.message}", file=sys.stderr)
            key = e["event_type"].rsplit(".", 1)[0]
            v = data_v.get(key)
            if v:
                for err in v.iter_errors(e["data"]):
                    errors += 1
                    if errors < 20:
                        print(f"data {e['event_type']} {e['event_id']}: {err.message}", file=sys.stderr)
    return errors


def main() -> None:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--out", default=str(HERE / "fixtures"))
    ap.add_argument("--seed", type=int, default=42)
    ap.add_argument("--anchor", default="now", help="'now' ou ISO-8601 (fim da janela simulada)")
    ap.add_argument("--days", type=int, default=210, help="tamanho da janela simulada em dias")
    ap.add_argument("--citizens", type=int, default=400)
    ap.add_argument("--no-production", action="store_true", help="não gera sus.production.* (exercita o modelo vazio tolerante)")
    ap.add_argument("--no-validate", action="store_true")
    args = ap.parse_args()

    anchor = datetime.now(timezone.utc).replace(microsecond=0) if args.anchor == "now" else datetime.fromisoformat(args.anchor)
    g = Gen(args.seed, anchor, args.days, not args.no_production)
    g.build_tenant("ibge_3143302", args.citizens, 6)
    g.build_tenant("ibge_3106200", max(30, args.citizens // 10), 2)  # município pequeno: exercita supressão

    if not args.no_validate:
        n = validate(g.out)
        if n:
            sys.exit(f"{n} eventos inválidos contra os contratos")

    out = Path(args.out)
    out.mkdir(parents=True, exist_ok=True)
    for old in out.glob("sus.*.jsonl"):
        old.unlink()
    total = 0
    for topic, events in sorted(g.out.items()):
        events.sort(key=lambda e: e["published_at"])
        with (out / f"{topic}.jsonl").open("w", encoding="utf-8") as fh:
            for e in events:
                fh.write(json.dumps(e, ensure_ascii=False) + "\n")
        total += len(events)
        print(f"{topic:36s} {len(events):6d}")
    (out / "health_units.json").write_text(json.dumps(g.units, ensure_ascii=False, indent=2))
    print(f"total {total} eventos → {out} (anchor {anchor.isoformat()})")


if __name__ == "__main__":
    main()
