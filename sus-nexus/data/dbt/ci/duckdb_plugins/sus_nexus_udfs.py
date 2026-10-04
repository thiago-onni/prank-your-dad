"""Plugin dbt-duckdb: registra UDFs com paridade às funções do Trino usadas pelos modelos.

- hmac_sha256_hex(value, key) ≡ lower(to_hex(hmac_sha256(to_utf8(value), to_utf8(key)))) no Trino.
"""

from __future__ import annotations

import hashlib
import hmac

from dbt.adapters.duckdb.plugins import BasePlugin


def _hmac_sha256_hex(value: str | None, key: str | None) -> str | None:
    if value is None or key is None:
        return None
    return hmac.new(key.encode("utf-8"), value.encode("utf-8"), hashlib.sha256).hexdigest()


class Plugin(BasePlugin):
    def configure_connection(self, conn) -> None:  # type: ignore[no-untyped-def]
        try:
            conn.create_function("hmac_sha256_hex", _hmac_sha256_hex, ["VARCHAR", "VARCHAR"], "VARCHAR",
                                 null_handling="special", side_effects=False)
        except Exception as exc:  # já registrada nesta conexão
            if "already exists" not in str(exc):
                raise
