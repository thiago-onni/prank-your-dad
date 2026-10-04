#!/usr/bin/env python3
"""Gerador de dados sintéticos brasileiros para o SUS Nexus (SEC-006).

Gera cidadãos (CitizenRegistration), agendamentos (AppointmentRegistration) e unidades
(HealthUnit) conformes ao contrato `contracts/openapi/core-municipal.yaml`, com:
- CNS e CPF com dígitos verificadores válidos, porém fictícios;
- duplicidades intencionais (mesma pessoa com grafia diferente, com/sem CNS) para testar o MPI;
- conflitos intencionais (mesmo CNS com data de nascimento diferente);
- distribuição territorial por UBS/equipe/microárea.

Uso:
  python3 generate.py --citizens 1000 --out ./out --seed 42 --tenant ibge_3143302
Saída: out/health_units.json, out/citizens.jsonl, out/appointments.jsonl, out/ground_truth.json
"""

from __future__ import annotations

import argparse
import json
import random
import unicodedata
from datetime import date, datetime, timedelta, timezone
from pathlib import Path

FIRST_F = ["Maria", "Ana", "Francisca", "Antônia", "Adriana", "Juliana", "Márcia", "Fernanda", "Patrícia", "Aline", "Sandra", "Camila", "Amanda", "Bruna", "Jéssica", "Letícia", "Júlia", "Luciana", "Vanessa", "Mariana", "Gabriela", "Vera", "Vitória", "Larissa", "Cláudia", "Beatriz", "Rafaela", "Rita", "Lúcia", "Sônia"]
FIRST_M = ["José", "João", "Antônio", "Francisco", "Carlos", "Paulo", "Pedro", "Lucas", "Luiz", "Marcos", "Luís", "Gabriel", "Rafael", "Daniel", "Marcelo", "Bruno", "Eduardo", "Felipe", "Raimundo", "Rodrigo", "Manoel", "Sebastião", "Geraldo", "Mateus", "André", "Fábio", "Leonardo", "Tiago", "Vinícius", "Sérgio"]
SURNAMES = ["Silva", "Santos", "Oliveira", "Souza", "Rodrigues", "Ferreira", "Alves", "Pereira", "Lima", "Gomes", "Costa", "Ribeiro", "Martins", "Carvalho", "Almeida", "Lopes", "Soares", "Fernandes", "Vieira", "Barbosa", "Rocha", "Dias", "Nascimento", "Andrade", "Moreira", "Nunes", "Marques", "Machado", "Mendes", "Freitas", "Cardoso", "Ramos", "Gonçalves", "Santana", "Teixeira"]
STREETS = ["Rua das Flores", "Avenida Brasil", "Rua São José", "Rua Minas Gerais", "Travessa da Paz", "Rua Sete de Setembro", "Avenida Getúlio Vargas", "Rua Tiradentes", "Rua Dom Pedro II", "Rua Padre Anchieta"]
DISTRICTS = ["Centro", "Vila Nova", "Jardim América", "Santa Rita", "Boa Vista", "São Judas", "Alto da Serra", "Morada do Sol", "Cidade Nova", "Independência"]
UNIT_NAMES = ["UBS Centro", "UBS Vila Nova", "ESF Jardim América", "UBS Santa Rita", "ESF Boa Vista", "UBS São Judas", "ESF Alto da Serra", "UBS Morada do Sol", "ESF Cidade Nova", "UBS Independência"]
SIGTAP = [("0301010072", "CONSULTA MEDICA EM ATENCAO BASICA"), ("0301010048", "CONSULTA DE PROFISSIONAIS DE NIVEL SUPERIOR NA ATENCAO BASICA (EXCETO MEDICO)"), ("0202010503", "DOSAGEM DE GLICOSE"), ("0202010473", "DOSAGEM DE COLESTEROL TOTAL"), ("0204030153", "MAMOGRAFIA BILATERAL PARA RASTREAMENTO"), ("0205020038", "ULTRASSONOGRAFIA OBSTETRICA"), ("0301010030", "CONSULTA DE PROFISSIONAIS DE NIVEL SUPERIOR NA ATENCAO ESPECIALIZADA"), ("0211020036", "ELETROCARDIOGRAMA")]


def cpf_digits(base: str) -> str:
    def dv(nums: str) -> str:
        s = sum(int(d) * w for d, w in zip(nums, range(len(nums) + 1, 1, -1)))
        r = (s * 10) % 11
        return "0" if r == 10 else str(r)

    d1 = dv(base)
    d2 = dv(base + d1)
    return base + d1 + d2


def random_cpf(rng: random.Random) -> str:
    while True:
        base = "".join(rng.choice("0123456789") for _ in range(9))
        if len(set(base)) > 1:
            return cpf_digits(base)


def cns_definitivo(rng: random.Random) -> str:
    """CNS definitivo (inicia em 1 ou 2), derivado de um PIS fictício, com DV oficial."""
    while True:
        pis = rng.choice("12") + "".join(rng.choice("0123456789") for _ in range(10))
        soma = sum(int(d) * (15 - i) for i, d in enumerate(pis))
        resto = soma % 11
        dv = 11 - resto
        if dv == 11:
            dv = 0
        if dv == 10:
            soma += 2
            resto = soma % 11
            dv = 11 - resto
            cns = pis + "001" + str(dv)
        else:
            cns = pis + "000" + str(dv)
        if cns_valido(cns):
            return cns


def cns_provisorio(rng: random.Random) -> str:
    """CNS provisório (inicia em 7, 8 ou 9): soma ponderada divisível por 11."""
    while True:
        d = rng.choice("789") + "".join(rng.choice("0123456789") for _ in range(13))
        soma = sum(int(x) * (15 - i) for i, x in enumerate(d))
        resto = soma % 11
        last = (11 - resto) % 11
        if last == 10:
            continue
        cns = d + str(last)
        if cns_valido(cns):
            return cns


def cns_valido(cns: str) -> bool:
    if len(cns) != 15 or not cns.isdigit():
        return False
    return sum(int(x) * (15 - i) for i, x in enumerate(cns)) % 11 == 0


def strip_accents(s: str) -> str:
    return "".join(c for c in unicodedata.normalize("NFD", s) if unicodedata.category(c) != "Mn")


def typo(s: str, rng: random.Random) -> str:
    """Introduz variação de grafia plausível (acentos, abreviação, troca de letra)."""
    kind = rng.choice(["accent", "abbrev", "swap", "drop_middle"])
    parts = s.split()
    if kind == "accent":
        return strip_accents(s)
    if kind == "abbrev" and len(parts) > 2:
        parts[1] = parts[1][0] + "."
        return " ".join(parts)
    if kind == "swap" and len(parts[0]) > 3:
        p = list(parts[0])
        i = rng.randrange(1, len(p) - 1)
        p[i], p[i + 1] = p[i + 1], p[i]
        parts[0] = "".join(p)
        return " ".join(parts)
    if kind == "drop_middle" and len(parts) > 2:
        return " ".join([parts[0]] + parts[2:])
    return strip_accents(s)


def iso(dt: datetime) -> str:
    return dt.astimezone(timezone(timedelta(hours=-3))).isoformat(timespec="seconds")


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--citizens", type=int, default=500)
    ap.add_argument("--duplicates-pct", type=float, default=0.08)
    ap.add_argument("--conflicts-pct", type=float, default=0.02)
    ap.add_argument("--no-cns-pct", type=float, default=0.15)
    ap.add_argument("--appointments-per-citizen", type=float, default=1.5)
    ap.add_argument("--units", type=int, default=10)
    ap.add_argument("--tenant", default="ibge_3143302")
    ap.add_argument("--seed", type=int, default=42)
    ap.add_argument("--out", default="./out")
    args = ap.parse_args()

    rng = random.Random(args.seed)
    out = Path(args.out)
    out.mkdir(parents=True, exist_ok=True)
    city_ibge = args.tenant.replace("ibge_", "")

    units = []
    for i in range(args.units):
        cnes = f"{rng.randrange(1000000, 9999999)}"
        units.append({
            "id": f"hu_{i:03d}",
            "cnes": cnes,
            "name": UNIT_NAMES[i % len(UNIT_NAMES)] + ("" if i < len(UNIT_NAMES) else f" {i}"),
            "kind_code": "02",
            "kind_description": "CENTRO DE SAUDE/UNIDADE BASICA",
            "address": f"{rng.choice(STREETS)}, {rng.randrange(1, 2000)} - {DISTRICTS[i % len(DISTRICTS)]}",
            "active": True,
            "teams": [{"ine": f"{rng.randrange(1000000000, 9999999999)}", "microareas": [f"{m:02d}" for m in range(1, 7)]} for _ in range(rng.choice([1, 2, 3]))],
        })
    (out / "health_units.json").write_text(json.dumps(units, ensure_ascii=False, indent=2))

    citizens: list[dict] = []
    truth: dict[str, list[str]] = {}
    used_cns: set[str] = set()
    used_cpf: set[str] = set()

    def new_person(idx: int) -> dict:
        sex = rng.choice(["female", "male"])
        first = rng.choice(FIRST_F if sex == "female" else FIRST_M)
        name = f"{first} {rng.choice(SURNAMES)} {rng.choice(SURNAMES)}"
        mother = f"{rng.choice(FIRST_F)} {rng.choice(SURNAMES)} {rng.choice(SURNAMES)}"
        birth = date(1930, 1, 1) + timedelta(days=rng.randrange(0, 35000))
        unit = rng.choice(units)
        team = rng.choice(unit["teams"])
        idents = []
        if rng.random() > args.no_cns_pct:
            cns = cns_definitivo(rng) if rng.random() < 0.8 else cns_provisorio(rng)
            used_cns.add(cns)
            idents.append({"system": "CNS", "value": cns})
        if rng.random() < 0.7:
            cpf = random_cpf(rng)
            used_cpf.add(cpf)
            idents.append({"system": "CPF", "value": cpf})
        idents.append({"system": "PEC", "value": f"PEC-{idx:07d}"})
        return {
            "source": {"system": "ESUS_APS_PEC", "connector": "connector-pec", "source_record_id": f"PEC-{idx:07d}", "source_record_version": "1", "cnes": unit["cnes"]},
            "identifiers": idents,
            "demographics": {"legal_name": name, "mother_name": mother, "birthdate": birth.isoformat(), "sex": sex, "race_color": rng.choice(["branca", "preta", "parda", "amarela", "indigena"]), "nationality": "BR", "deceased": False},
            "address": {"street": rng.choice(STREETS), "number": str(rng.randrange(1, 3000)), "district": rng.choice(DISTRICTS), "city_ibge": city_ibge, "postal_code": f"{rng.randrange(39400000, 39409999)}"},
            "contacts": [{"kind": "mobile", "value": f"+5538{rng.randrange(900000000, 999999999)}"}],
            "territory": {"health_unit_cnes": unit["cnes"], "team_ine": team["ine"], "microarea": rng.choice(team["microareas"])},
        }

    idx = 0
    for _ in range(args.citizens):
        idx += 1
        p = new_person(idx)
        citizens.append(p)
        truth[p["source"]["source_record_id"]] = [p["source"]["source_record_id"]]
        r = rng.random()
        if r < args.duplicates_pct:
            # duplicata: mesma pessoa, grafia diferente, vinda de outro sistema, às vezes sem CNS
            idx += 1
            d = json.loads(json.dumps(p))
            d["source"] = {"system": "HIS_HOSPITAL_MUNICIPAL", "connector": "connector-his", "source_record_id": f"HIS-{idx:07d}", "source_record_version": "1", "cnes": rng.choice(units)["cnes"]}
            d["demographics"]["legal_name"] = typo(p["demographics"]["legal_name"], rng)
            if rng.random() < 0.5:
                d["demographics"]["mother_name"] = typo(p["demographics"]["mother_name"], rng)
            keep = rng.random()
            d["identifiers"] = [i for i in p["identifiers"] if i["system"] != "PEC" and (keep > 0.4 or i["system"] != "CNS")]
            d["identifiers"].append({"system": "HIS", "value": f"HIS-{idx:07d}"})
            citizens.append(d)
            truth[p["source"]["source_record_id"]].append(d["source"]["source_record_id"])
        elif r < args.duplicates_pct + args.conflicts_pct:
            # conflito: mesmo CNS, data de nascimento diferente — NÃO deve fundir automaticamente
            cns = next((i["value"] for i in p["identifiers"] if i["system"] == "CNS"), None)
            if cns:
                idx += 1
                c = new_person(idx)
                c["identifiers"] = [i for i in c["identifiers"] if i["system"] != "CNS"] + [{"system": "CNS", "value": cns}]
                c["source"]["system"] = "SISREG"
                c["source"]["connector"] = "connector-sisreg"
                c["source"]["source_record_id"] = f"SISREG-{idx:07d}"
                citizens.append(c)
                truth.setdefault("__conflicts__", []).append(f"{p['source']['source_record_id']}|{c['source']['source_record_id']}")

    rng.shuffle(citizens)
    with (out / "citizens.jsonl").open("w") as f:
        for c in citizens:
            f.write(json.dumps(c, ensure_ascii=False) + "\n")

    now = datetime.now(timezone.utc)
    n_apts = 0
    with (out / "appointments.jsonl").open("w") as f:
        for c in citizens:
            for _ in range(int(rng.expovariate(1 / args.appointments_per_citizen))):
                n_apts += 1
                start = now + timedelta(days=rng.randrange(-180, 60), hours=rng.randrange(7, 17))
                code, _desc = rng.choice(SIGTAP)
                past = start < now
                status = rng.choices(["fulfilled", "noshow", "cancelled"], [0.7, 0.2, 0.1])[0] if past else rng.choices(["booked", "confirmed", "waitlist"], [0.6, 0.3, 0.1])[0]
                ident = next((i for i in c["identifiers"] if i["system"] in ("CNS", "CPF")), c["identifiers"][-1])
                f.write(json.dumps({
                    "source": {"system": c["source"]["system"], "connector": c["source"]["connector"], "source_record_id": f"AGD-{n_apts:08d}", "source_record_version": "1", "cnes": c["territory"]["health_unit_cnes"]},
                    "citizen_ref": {"identifier_system": ident["system"], "identifier_value": ident["value"]},
                    "status": status,
                    "kind": rng.choices(["direct", "regulated", "return", "walk_in"], [0.6, 0.2, 0.15, 0.05])[0],
                    "service_code": code,
                    "code_system": "SIGTAP",
                    "health_unit_cnes": c["territory"]["health_unit_cnes"],
                    "scheduled_start": iso(start),
                    "scheduled_end": iso(start + timedelta(minutes=20)),
                    "occurred_at": iso(min(start, now)),
                    **({"cancellation_reason": rng.choice(["paciente_solicitou", "profissional_ausente", "sem_transporte"])} if status == "cancelled" else {}),
                }, ensure_ascii=False) + "\n")

    (out / "ground_truth.json").write_text(json.dumps(truth, ensure_ascii=False, indent=2))
    print(f"unidades={len(units)} cidadãos={len(citizens)} agendamentos={n_apts} grupos_duplicados={sum(1 for v in truth.values() if len(v) > 1)} conflitos={len(truth.get('__conflicts__', []))}")
    print(f"saída em {out.resolve()}")


if __name__ == "__main__":
    main()
