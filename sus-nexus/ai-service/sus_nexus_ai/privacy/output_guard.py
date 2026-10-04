"""Bloqueio de PII na **saída** do LLM (pós-geração).

Detecta, em texto livre gerado:

* CPF (formatado ou 11 dígitos), CNS (15 dígitos), telefone, e-mail;
* tokens de pseudônimo (``[PESSOA_n]``) — a saída agregada nunca fala de pessoas;
* nomes de pessoa por heurística: pronome de tratamento (``Dr.``, ``Sra.``…) ou papel
  (``paciente``, ``cidadão``…) seguido de palavra capitalizada, ou prenome brasileiro comum seguido
  de sobrenome capitalizado. Trechos que aparecem literalmente nos dados de origem (ex.: nome de
  unidade "UBS Maria da Glória") são permitidos.

As mensagens de problema nunca ecoam o valor detectado (podem ir ao LLM e a logs).
"""

from __future__ import annotations

import re
from collections.abc import Iterable

_CPF_RE = re.compile(r"(?<!\d)\d{3}\.?\d{3}\.?\d{3}-?\d{2}(?!\d)")
_CNS_RE = re.compile(r"(?<!\d)[1-9]\d{2}\s?\d{4}\s?\d{4}\s?\d{4}(?!\d)")
_PHONE_RE = re.compile(r"(?<!\d)(?:\+?55\s?)?\(\d{2}\)\s?9?\d{4}[-\s]?\d{4}(?!\d)")
_EMAIL_RE = re.compile(r"[\w.+-]+@[\w-]+\.[\w.-]+")
_PSEUDONYM_RE = re.compile(r"\[PESSOA_\d+\]")

_CAP = r"[A-ZÀ-Ý][a-zà-ÿ]+"
_CONNECTOR = r"(?:\s+(?:da|de|do|das|dos|e))?"
_HONORIFIC_RE = re.compile(
    rf"\b(?:Dr|Dra|Sr|Sra|Srta|Prof|Profa)\.?\s+{_CAP}(?:{_CONNECTOR}\s+{_CAP})*"
)
_ROLE_RE = re.compile(
    r"\b(?i:paciente|cidad[ãa]o|cidad[ãa]|usu[áa]ri[oa]|m[ãa]e|pai|gestante|munícipe)\s+"
    rf"{_CAP}(?:{_CONNECTOR}\s+{_CAP})*"
)

# Prenomes mais frequentes no Brasil (IBGE, Censo 2010) — base para a heurística de nome completo.
COMMON_FIRST_NAMES: frozenset[str] = frozenset(
    """
    maria jose joao ana antonio francisco carlos paulo pedro lucas luiz marcos luis gabriel
    rafael daniel marcelo bruno eduardo felipe raimundo rodrigo manoel mateus andre fernando
    fabio leonardo gustavo guilherme leandro tiago anderson ricardo marcio jorge sebastiao
    alexandre roberto edson diego vitor sergio claudio matheus thiago geraldo adriano luciano
    julio renato alex vinicius rogerio samuel ronaldo mario flavio douglas igor davi manuel
    jefferson cicero victor miguel robson mauro danilo francisca antonia adriana juliana marcia
    fernanda patricia aline sandra camila amanda bruna jessica leticia julia luciana vanessa
    mariana gabriela vera vitoria larissa claudia beatriz luana rita sonia renata eliane josefa
    simone natalia cristiane carla debora rosangela jaqueline rosa daniela aparecida marlene
    terezinha raimunda andreia fabiana lucia raquel angela rafaela joana luzia elaine daiane
    regina helena joaquim benedito valdir
    """.split()  # noqa: SIM905
)

_FOLD = str.maketrans(
    "áàâãäéèêëíìîïóòôõöúùûüçÁÀÂÃÄÉÈÊËÍÌÎÏÓÒÔÕÖÚÙÛÜÇ",
    "aaaaaeeeeiiiiooooouuuucAAAAAEEEEIIIIOOOOOUUUUC",
)
_WORD_SEQ_RE = re.compile(rf"{_CAP}(?:{_CONNECTOR}\s+{_CAP})+")


def _fold(text: str) -> str:
    return text.translate(_FOLD).casefold()


def _allowed(span: str, allowed_texts: Iterable[str]) -> bool:
    folded = _fold(span)
    return any(folded in _fold(t) for t in allowed_texts)


def find_pii(text: str, allowed_texts: Iterable[str] = ()) -> list[str]:
    """Categorias de PII encontradas (sem o valor). ``allowed_texts``: textos de origem."""
    allowed = [t for t in allowed_texts if t]
    found: list[str] = []
    if _CPF_RE.search(text):
        found.append("cpf")
    if _CNS_RE.search(text):
        found.append("cns")
    if _PHONE_RE.search(text):
        found.append("telefone")
    if _EMAIL_RE.search(text):
        found.append("email")
    if _PSEUDONYM_RE.search(text):
        found.append("pseudonimo_de_pessoa")
    for regex in (_HONORIFIC_RE, _ROLE_RE):
        for match in regex.finditer(text):
            if not _allowed(match.group(0), allowed):
                found.append("nome_de_pessoa")
                break
    if "nome_de_pessoa" not in found:
        for match in _WORD_SEQ_RE.finditer(text):
            words = match.group(0).split()
            if _fold(words[0]) in COMMON_FIRST_NAMES and not _allowed(match.group(0), allowed):
                found.append("nome_de_pessoa")
                break
    return sorted(set(found))
