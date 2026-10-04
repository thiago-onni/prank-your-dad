"""Cliente mínimo da API HTTP do Trino (``POST /v1/statement`` + ``nextUri``).

Usado **somente** pelas ferramentas do agente de BI (camada ``marts_aggregated``). Regras:

* o SQL vem sempre de um *template fixo* do código (``tools/analytics.py``) com marcadores ``?``;
  nenhum texto do LLM ou do usuário vira SQL;
* os parâmetros são validados antes (whitelist/regex) e enviados ao Trino como parâmetros de
  ``EXECUTE IMMEDIATE '<template>' USING <literais>`` — o Trino faz o *binding*, o template não é
  concatenado com valores;
* a paginação segue ``nextUri`` apenas no mesmo host/porta configurado (credenciais nunca vão a
  outro destino) e com limite de iterações;
* erros do Trino viram ``TrinoQueryError`` sem ecoar o SQL nem os valores (logs sem dados).

Protocolo: https://trino.io/docs/current/develop/client-protocol.html
"""

from __future__ import annotations

import asyncio
import math
from collections.abc import Sequence
from typing import Any
from urllib.parse import urlsplit

import httpx

from sus_nexus_ai.common import get_logger

log = get_logger(__name__)

SqlParam = str | int | float | bool | None

RETRYABLE_STATUS = frozenset({429, 502, 503, 504})


class TrinoError(RuntimeError):
    """Falha de transporte/protocolo com o Trino."""


class TrinoQueryError(TrinoError):
    """O Trino recusou ou falhou a consulta (``error`` no corpo)."""

    def __init__(self, error_name: str, error_type: str | None = None) -> None:
        self.error_name = error_name
        self.error_type = error_type
        super().__init__(f"trino:{error_name}")


def render_literal(value: SqlParam) -> str:
    """Literal SQL do Trino para um parâmetro já validado (``USING``)."""
    if value is None:
        return "NULL"
    if isinstance(value, bool):
        return "TRUE" if value else "FALSE"
    if isinstance(value, int):
        return str(value)
    if isinstance(value, float):
        if not math.isfinite(value):
            raise ValueError("parâmetro numérico não finito")
        return f"DOUBLE '{value!r}'"
    if isinstance(value, str):
        if any(ord(ch) < 32 for ch in value):
            raise ValueError("parâmetro com caractere de controle")
        return "'" + value.replace("'", "''") + "'"
    raise TypeError(f"tipo de parâmetro não suportado: {type(value).__name__}")


def build_statement(template: str, params: Sequence[SqlParam]) -> str:
    """Monta ``EXECUTE IMMEDIATE`` com o template fixo e os parâmetros como literais."""
    placeholders = template.count("?")
    if placeholders != len(params):
        raise ValueError(f"template espera {placeholders} parâmetro(s), recebeu {len(params)}")
    if not params:
        return template
    using = ", ".join(render_literal(p) for p in params)
    return f"EXECUTE IMMEDIATE {render_literal(template)} USING {using}"


class TrinoHttpClient:
    def __init__(
        self,
        base_url: str,
        user: str,
        catalog: str,
        *,
        schema: str | None = None,
        source: str = "sus-nexus-ai-service",
        password: str | None = None,
        timeout_seconds: float = 30.0,
        max_polls: int = 500,
        poll_interval_seconds: float = 0.05,
        client: httpx.AsyncClient | None = None,
    ) -> None:
        self.base_url = base_url.rstrip("/")
        self.user = user
        self.catalog = catalog
        self.schema = schema
        self.source = source
        self.max_polls = max_polls
        self.poll_interval_seconds = poll_interval_seconds
        origin = urlsplit(self.base_url)
        self._origin = (origin.scheme, origin.netloc)
        auth = httpx.BasicAuth(user, password) if password else None
        self._client = client or httpx.AsyncClient(timeout=timeout_seconds, auth=auth)

    def _headers(self, correlation_id: str | None) -> dict[str, str]:
        headers = {
            "X-Trino-User": self.user,
            "X-Trino-Catalog": self.catalog,
            "X-Trino-Source": self.source,
            "Content-Type": "text/plain; charset=utf-8",
        }
        if self.schema:
            headers["X-Trino-Schema"] = self.schema
        if correlation_id:
            headers["X-Trino-Trace-Token"] = correlation_id
        return headers

    def _check_next_uri(self, next_uri: str) -> str:
        parts = urlsplit(next_uri)
        if (parts.scheme, parts.netloc) != self._origin:
            raise TrinoError("nextUri fora do host configurado")
        return next_uri

    async def _send(
        self, method: str, url: str, headers: dict[str, str], content: str | None
    ) -> Any:
        attempt = 0
        while True:
            attempt += 1
            try:
                resp = await self._client.request(method, url, headers=headers, content=content)
            except httpx.HTTPError as exc:
                raise TrinoError(f"transporte:{type(exc).__name__}") from exc
            if resp.status_code in RETRYABLE_STATUS and attempt < 4:
                await asyncio.sleep(self.poll_interval_seconds * attempt)
                continue
            if resp.status_code != 200:
                raise TrinoError(f"http:{resp.status_code}")
            try:
                return resp.json()
            except ValueError as exc:
                raise TrinoError("resposta não-JSON") from exc

    async def query(
        self,
        template: str,
        params: Sequence[SqlParam] = (),
        *,
        correlation_id: str | None = None,
    ) -> list[dict[str, Any]]:
        """Executa um template fixo e devolve as linhas como dicionários (coluna → valor)."""
        statement = build_statement(template, params)
        headers = self._headers(correlation_id)
        body = await self._send("POST", f"{self.base_url}/v1/statement", headers, statement)
        columns: list[str] | None = None
        rows: list[list[Any]] = []
        polls = 0
        while True:
            if not isinstance(body, dict):
                raise TrinoError("corpo inválido")
            error = body.get("error")
            if error:
                name = str(error.get("errorName") or "UNKNOWN") if isinstance(error, dict) else "?"
                etype = error.get("errorType") if isinstance(error, dict) else None
                log.warning("trino.query_failed", error=name, correlation_id=correlation_id)
                raise TrinoQueryError(name, str(etype) if etype else None)
            if columns is None and body.get("columns"):
                columns = [str(c["name"]) for c in body["columns"]]
            data = body.get("data")
            if data:
                rows.extend(data)
            next_uri = body.get("nextUri")
            if not next_uri:
                break
            polls += 1
            if polls > self.max_polls:
                raise TrinoError("limite de paginação excedido")
            body = await self._send("GET", self._check_next_uri(str(next_uri)), headers, None)
        if columns is None:
            return []
        return [dict(zip(columns, row, strict=False)) for row in rows]

    async def aclose(self) -> None:
        await self._client.aclose()
