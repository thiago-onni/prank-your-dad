#!/usr/bin/env python3
"""Provisionamento do Metabase via API (sem dependências externas). Ver provision.sh."""

from __future__ import annotations

import json
import os
import sys
import time
import urllib.error
import urllib.request

MB_URL = os.environ.get("MB_URL", "http://localhost:3002").rstrip("/")
EMAIL = os.environ.get("MB_ADMIN_EMAIL", "admin@sus-nexus.local")
PASSWORD = os.environ.get("MB_ADMIN_PASSWORD", "SusNexus-dev-2026")
DRY_RUN = os.environ.get("DRY_RUN", "false").lower() == "true"
SESSION: dict[str, str] = {}


def api(method: str, path: str, body: dict | None = None) -> dict | list:
    data = json.dumps(body).encode() if body is not None else None
    req = urllib.request.Request(f"{MB_URL}/api{path}", data=data, method=method)
    req.add_header("Content-Type", "application/json")
    if "id" in SESSION:
        req.add_header("X-Metabase-Session", SESSION["id"])
    try:
        with urllib.request.urlopen(req, timeout=60) as resp:
            raw = resp.read()
            return json.loads(raw) if raw else {}
    except urllib.error.HTTPError as exc:
        raise SystemExit(f"{method} {path} → HTTP {exc.code}: {exc.read().decode()[:500]}") from exc


def wait_health() -> None:
    for _ in range(120):
        try:
            with urllib.request.urlopen(f"{MB_URL}/api/health", timeout=5) as resp:
                if resp.status == 200:
                    return
        except Exception:
            pass
        time.sleep(3)
    raise SystemExit(f"Metabase indisponível em {MB_URL}")


def login() -> None:
    props = api("GET", "/session/properties")
    token = props.get("setup-token") if isinstance(props, dict) else None
    if token and not props.get("has-user-setup"):
        print(">> setup inicial do Metabase")
        res = api("POST", "/setup", {
            "token": token,
            "user": {"email": EMAIL, "password": PASSWORD, "first_name": "Admin", "last_name": "SUS Nexus", "site_name": "SUS Nexus"},
            "prefs": {"site_name": "SUS Nexus", "site_locale": "pt_BR", "allow_tracking": False},
        })
        SESSION["id"] = res["id"]
        return
    SESSION["id"] = api("POST", "/session", {"username": EMAIL, "password": PASSWORD})["id"]


def upsert_database(spec: dict) -> int:
    details = dict(spec["details"])
    details["host"] = os.environ.get("TRINO_HOST", details["host"])
    details["port"] = int(os.environ.get("TRINO_PORT", details["port"]))
    details["user"] = os.environ.get("TRINO_USER", details["user"])
    dbs = api("GET", "/database")
    dbs = dbs.get("data", dbs) if isinstance(dbs, dict) else dbs
    existing = next((d for d in dbs if d["name"] == spec["name"]), None)
    body = {"name": spec["name"], "engine": spec["engine"], "details": details, "is_full_sync": True}
    if existing:
        api("PUT", f"/database/{existing['id']}", body)
        db_id = existing["id"]
    else:
        db_id = api("POST", "/database", body)["id"]
    api("POST", f"/database/{db_id}/sync_schema")
    print(f">> conexão Trino: database id {db_id}")
    return db_id


def upsert_collection(name: str) -> int:
    for c in api("GET", "/collection"):
        if c.get("name") == name and not c.get("archived"):
            return c["id"]
    return api("POST", "/collection", {"name": name, "color": "#1351B4"})["id"]


def upsert_card(card: dict, db_id: int, coll_id: int, tag: str) -> int:
    native: dict = {"query": card["sql"]}
    if "{{" + tag + "}}" in card["sql"]:
        native["template-tags"] = {tag: {"id": f"{card['key']}-{tag}", "name": tag, "display-name": "Município (ibge_…)", "type": "text"}}
    body = {
        "name": card["name"],
        "description": card["description"],
        "display": card["display"],
        "collection_id": coll_id,
        "dataset_query": {"type": "native", "database": db_id, "native": native},
        "visualization_settings": card.get("viz", {}),
    }
    found = [c for c in api("GET", f"/collection/{coll_id}/items?models=card")["data"] if c["name"] == card["name"]]
    if found:
        api("PUT", f"/card/{found[0]['id']}", body)
        return found[0]["id"]
    return api("POST", "/card", body)["id"]


def upsert_dashboard(spec: dict, coll_id: int, card_ids: dict[str, int]) -> int:
    found = [d for d in api("GET", f"/collection/{coll_id}/items?models=dashboard")["data"] if d["name"] == spec["name"]]
    dash_id = found[0]["id"] if found else api("POST", "/dashboard", {"name": spec["name"], "description": spec["description"], "collection_id": coll_id})["id"]
    p = spec["parameter"]
    param = {"id": p["slug"], "name": p["name"], "slug": p["slug"], "type": p["type"]}
    dashcards = []
    for i, card in enumerate(spec["cards"], start=1):
        lay = card["layout"]
        mappings = []
        if "{{" + p["template_tag"] + "}}" in card["sql"]:
            mappings = [{"parameter_id": p["slug"], "card_id": card_ids[card["key"]], "target": ["variable", ["template-tag", p["template_tag"]]]}]
        dashcards.append({"id": -i, "card_id": card_ids[card["key"]], "row": lay["row"], "col": lay["col"],
                          "size_x": lay["size_x"], "size_y": lay["size_y"], "parameter_mappings": mappings})
    api("PUT", f"/dashboard/{dash_id}", {"description": spec["description"], "parameters": [param], "dashcards": dashcards})
    return dash_id


def main() -> None:
    spec = json.load(open(sys.argv[1], encoding="utf-8"))
    if DRY_RUN:
        print(f"painel '{spec['name']}' com {len(spec['cards'])} perguntas: " + ", ".join(c["key"] for c in spec["cards"]))
        return
    wait_health()
    login()
    db_id = upsert_database(spec["database"])
    coll_id = upsert_collection(spec["collection"])
    card_ids = {c["key"]: upsert_card(c, db_id, coll_id, spec["parameter"]["template_tag"]) for c in spec["cards"]}
    dash_id = upsert_dashboard(spec, coll_id, card_ids)
    print(f">> Sala de Situação: {MB_URL}/dashboard/{dash_id}")


if __name__ == "__main__":
    main()
