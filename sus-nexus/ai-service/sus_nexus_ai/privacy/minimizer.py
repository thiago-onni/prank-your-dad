"""Minimização e pseudonimização do contexto enviado ao LLM (AIA-007).

Regras:
* chaves de identificadores/contato/endereço são **removidas** (CPF, CNS, telefone, e-mail,
  endereço, CEP, contatos);
* chaves de nome de pessoa viram tokens ``[PESSOA_n]`` (mapa reversível em memória do run);
* valores em texto livre passam por regex (CPF, CNS, telefone, e-mail, CEP) → ``[REMOVIDO]``
  e por substituição dos nomes já conhecidos → token;
* IDs internos (``cit_…``, ``reg_…``) e atributos operacionais são mantidos — são pseudônimos.
"""

from __future__ import annotations

import re
from dataclasses import dataclass, field
from typing import Any

REDACTED = "[REMOVIDO]"

# Chaves (normalizadas: minúsculas, sem acento/underscore) cujo valor é removido inteiro.
DROP_KEYS: frozenset[str] = frozenset(
    {
        "cpf",
        "cns",
        "rg",
        "phone",
        "telefone",
        "celular",
        "mobile",
        "whatsapp",
        "email",
        "address",
        "endereco",
        "street",
        "logradouro",
        "number",
        "complement",
        "postalcode",
        "cep",
        "contacts",
        "contact",
        "identifiers",
        "identifier",
        "document",
        "documento",
    }
)

# Chaves cujo valor é um nome de pessoa → token [PESSOA_n].
NAME_KEYS: frozenset[str] = frozenset(
    {
        "name",
        "nome",
        "fullname",
        "legalname",
        "socialname",
        "displayname",
        "patientname",
        "nomepaciente",
        "citizenname",
        "mothername",
        "nomemae",
        "fathername",
        "nomepai",
        "requestedby",
        "professionalname",
        "nomeprofissional",
    }
)

# Chaves terminadas em "name" que NÃO são pessoas (mantidas). Qualquer outra ``*name`` é tokenizada:
# tokenizar demais só perde informação; deixar passar um nome é vazamento.
NON_PERSON_NAME_KEYS: frozenset[str] = frozenset(
    {
        "filename",
        "hostname",
        "unitname",
        "healthunitname",
        "teamname",
        "procedurename",
        "specialtyname",
        "systemname",
        "connectorname",
        "rulename",
        "templatename",
        "servicename",
        "agentname",
        "toolname",
        "topicname",
        "fieldname",
        "cityname",
        "municipalityname",
    }
)


def is_person_name_key(normalized_key: str) -> bool:
    if normalized_key in NAME_KEYS:
        return True
    return normalized_key.endswith("name") and normalized_key not in NON_PERSON_NAME_KEYS


_CPF_RE = re.compile(r"(?<!\d)\d{3}\.?\d{3}\.?\d{3}-?\d{2}(?!\d)")
_CNS_RE = re.compile(r"(?<!\d)\d{15}(?!\d)")
_PHONE_RE = re.compile(r"(?<!\d)(?:\+?55\s?)?\(?\d{2}\)?\s?9?\d{4}[-\s]?\d{4}(?!\d)")
_EMAIL_RE = re.compile(r"[\w.+-]+@[\w-]+\.[\w.-]+")
_CEP_RE = re.compile(r"(?<!\d)\d{5}-\d{3}(?!\d)")
_PATTERNS = (_CPF_RE, _CNS_RE, _EMAIL_RE, _CEP_RE, _PHONE_RE)


def _norm_key(key: str) -> str:
    return re.sub(r"[^a-z]", "", key.lower())


@dataclass
class PseudonymMap:
    """Mapa reversível token → nome. Vive apenas em memória durante o run."""

    tokens: dict[str, str] = field(default_factory=dict)
    _reverse: dict[str, str] = field(default_factory=dict, repr=False)

    def token_for(self, name: str) -> str:
        key = name.strip().casefold()
        if key in self._reverse:
            return self._reverse[key]
        token = f"[PESSOA_{len(self.tokens) + 1}]"
        self.tokens[token] = name.strip()
        self._reverse[key] = token
        return token

    def reidentify(self, text: str) -> str:
        for token, name in self.tokens.items():
            text = text.replace(token, name)
        return text

    def __len__(self) -> int:
        return len(self.tokens)


@dataclass
class MinimizationResult:
    data: Any
    pseudonyms: PseudonymMap
    removed_fields: list[str] = field(default_factory=list)
    redactions: int = 0


class Minimizer:
    def __init__(self, extra_drop_keys: set[str] | None = None) -> None:
        self._drop = DROP_KEYS | {_norm_key(k) for k in (extra_drop_keys or set())}

    def minimize(self, data: Any, pseudonyms: PseudonymMap | None = None) -> MinimizationResult:
        result = MinimizationResult(
            data=None, pseudonyms=pseudonyms if pseudonyms is not None else PseudonymMap()
        )
        # Passo 1: coletar nomes (para substituição em texto livre) e remover/tokenizar chaves.
        structured = self._walk(data, result, path="$")
        # Passo 2: regex + substituição de nomes conhecidos em todas as strings.
        result.data = self._scrub_strings(structured, result)
        return result

    # ---- internos ----
    def _walk(self, node: Any, result: MinimizationResult, path: str) -> Any:
        if isinstance(node, dict):
            out: dict[str, Any] = {}
            for key, value in node.items():
                nk = _norm_key(str(key))
                if nk in self._drop:
                    result.removed_fields.append(f"{path}.{key}")
                    continue
                if is_person_name_key(nk) and isinstance(value, str) and value.strip():
                    out[key] = result.pseudonyms.token_for(value)
                    continue
                out[key] = self._walk(value, result, f"{path}.{key}")
            return out
        if isinstance(node, list):
            return [self._walk(item, result, f"{path}[{i}]") for i, item in enumerate(node)]
        return node

    def _scrub_strings(self, node: Any, result: MinimizationResult) -> Any:
        if isinstance(node, dict):
            return {k: self._scrub_strings(v, result) for k, v in node.items()}
        if isinstance(node, list):
            return [self._scrub_strings(v, result) for v in node]
        if isinstance(node, str):
            return self.scrub_text(node, result)
        return node

    def scrub_text(self, text: str, result: MinimizationResult) -> str:
        if text.startswith("[PESSOA_"):
            return text
        for pattern in _PATTERNS:
            text, n = pattern.subn(REDACTED, text)
            result.redactions += n
        for token, name in result.pseudonyms.tokens.items():
            if len(name) < 3:
                continue
            new_text, n = re.subn(re.escape(name), token, text, flags=re.IGNORECASE)
            if n:
                text = new_text
                result.redactions += n
        return text


def mask_for_record(data: Any) -> Any:
    """Máscara para persistência (args de ferramentas, contextos): sem mapa reversível."""
    return Minimizer().minimize(data).data
