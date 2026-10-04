"""Exporta o OpenAPI do próprio ai-service para ``contracts/openapi/ai-service.yaml``.

Uso::

    python -m sus_nexus_ai.export_openapi            # grava o arquivo
    python -m sus_nexus_ai.export_openapi --check    # falha (código 1) se estiver desatualizado
    python -m sus_nexus_ai.export_openapi --out /caminho/ai-service.yaml

O web gera tipos a partir desse arquivo; ``tests/test_openapi_export.py`` garante que ele está
sincronizado com as rotas.
"""

from __future__ import annotations

import argparse
import sys
from pathlib import Path
from typing import Any

import yaml

from sus_nexus_ai.api.app import create_app
from sus_nexus_ai.config import PACKAGE_DIR, Settings

DEFAULT_OUTPUT = PACKAGE_DIR.parent.parent / "contracts" / "openapi" / "ai-service.yaml"
HEADER = "# Gerado por `python -m sus_nexus_ai.export_openapi` — não edite à mão.\n"


def build_spec() -> dict[str, Any]:
    settings = Settings(
        _env_file=None,
        environment="test",
        auth_mode="mock",
        policy_mode="local",
        identity_mode="fake",
        llm_provider="fake",
    )
    app = create_app(settings)
    spec: dict[str, Any] = app.openapi()
    return spec


def render(spec: dict[str, Any]) -> str:
    body: str = yaml.safe_dump(spec, sort_keys=False, allow_unicode=True, width=100)
    return HEADER + body


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(prog="python -m sus_nexus_ai.export_openapi")
    parser.add_argument("--out", type=Path, default=DEFAULT_OUTPUT)
    parser.add_argument("--check", action="store_true", help="só confere se está atualizado")
    args = parser.parse_args(argv)
    content = render(build_spec())
    if args.check:
        current = args.out.read_text("utf-8") if args.out.exists() else ""
        if current != content:
            print(f"{args.out}: desatualizado — rode python -m sus_nexus_ai.export_openapi")
            return 1
        print(f"{args.out}: atualizado")
        return 0
    args.out.parent.mkdir(parents=True, exist_ok=True)
    args.out.write_text(content, "utf-8")
    print(f"{args.out}: gravado ({len(content)} bytes)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
