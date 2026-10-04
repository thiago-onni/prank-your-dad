#!/usr/bin/env python3
"""Gera manifests Strimzi `KafkaTopic` a partir de contracts/events/topics.yaml.

Saída (default): platform/helm/sus-nexus/templates/kafka-topics.generated.yaml — um template Helm
(envolvido em `{{- if .Values.strimzi.enabled }}`) que usa .Values.strimzi para cluster/namespace e
replicação, de modo que dev (1 broker) e prod (3 brokers) usem o mesmo arquivo.

Uso:
  scripts/gen-kafka-topics.py                      # regenera o template
  scripts/gen-kafka-topics.py --check              # falha se o template estiver desatualizado (CI)
  scripts/gen-kafka-topics.py --plain -o out.yaml  # YAML puro (sem Helm) para kubectl apply
"""
from __future__ import annotations

import argparse
import pathlib
import sys

try:
    import yaml
except ImportError:  # pragma: no cover
    sys.exit("PyYAML necessário: pip install pyyaml")

HERE = pathlib.Path(__file__).resolve().parent
DEFAULT_TOPICS = HERE.parents[2] / "contracts" / "events" / "topics.yaml"
DEFAULT_OUT = HERE.parent / "sus-nexus" / "templates" / "kafka-topics.generated.yaml"

DAY_MS = 24 * 60 * 60 * 1000


def load(path: pathlib.Path) -> tuple[dict, list[dict], dict]:
    doc = yaml.safe_load(path.read_text(encoding="utf-8"))
    return doc.get("defaults", {}), doc["topics"], doc.get("retry_policy", {})


def topic_entries(defaults: dict, topics: list[dict], retry: dict) -> list[dict]:
    out = []
    for t in topics:
        cls = t.get("class", "domain")
        base = {
            "name": t["name"],
            "class": cls,
            "partitions": int(t.get("partitions", defaults.get("partitions", 12))),
            "replication": int(t.get("replication", defaults.get("replication", 3))),
            "min_isr": int(t.get("min_insync_replicas", defaults.get("min_insync_replicas", 2))),
            "retention_days": int(t.get("retention_days", defaults.get("retention_days", 30))),
            "key": t.get("key", defaults.get("key")),
            "compat": t.get("schema_compatibility", defaults.get("schema_compatibility", "BACKWARD")),
            "events": t.get("events", []),
        }
        out.append(base)
        if cls in ("domain", "ingest"):
            for suffix in retry.get("suffixes", [".retry.1", ".retry.2", ".retry.3"]):
                r = dict(base, name=f"{t['name']}{suffix}", retention_days=7, events=[], **{"class": "retry"})
                out.append(r)
    return out


def k8s_name(topic: str) -> str:
    return topic.replace(".", "-").replace("_", "-").lower()


def render(entries: list[dict], helm: bool) -> str:
    lines: list[str] = []
    lines.append("# GERADO por platform/helm/scripts/gen-kafka-topics.py a partir de contracts/events/topics.yaml.")
    lines.append("# NÃO edite manualmente — rode o script (o CI verifica com --check).")
    if helm:
        lines.append("{{- if .Values.strimzi.enabled }}")
        lines.append("{{- $ns := .Values.strimzi.namespace | default .Values.global.namespaces.data }}")
        lines.append("{{- $cluster := .Values.strimzi.clusterName }}")
        lines.append("{{- $rf := int .Values.strimzi.topicReplication }}")
        lines.append("{{- $minIsr := int .Values.strimzi.topicMinInsyncReplicas }}")
        lines.append("{{- $pf := .Values.strimzi.partitionFactor | default 1.0 }}")
    for e in entries:
        parts = e["partitions"]
        lines.append("---")
        lines.append("apiVersion: kafka.strimzi.io/v1beta2")
        lines.append("kind: KafkaTopic")
        lines.append("metadata:")
        lines.append(f"  name: {k8s_name(e['name'])}")
        lines.append(f"  namespace: {'{{ $ns }}' if helm else 'data'}")
        lines.append("  labels:")
        lines.append(f"    strimzi.io/cluster: {'{{ $cluster }}' if helm else 'sus-kafka'}")
        lines.append("    app.kubernetes.io/part-of: sus-nexus")
        lines.append(f"    sus-nexus.gov.br/topic-class: {e['class']}")
        lines.append("  annotations:")
        lines.append(f"    sus-nexus.gov.br/topic: {e['name']}")
        if e["key"]:
            lines.append(f"    sus-nexus.gov.br/partition-key: {e['key']}")
        if e["events"]:
            lines.append(f"    sus-nexus.gov.br/events: \"{','.join(e['events'])}\"")
        lines.append(f"    sus-nexus.gov.br/schema-compatibility: {e['compat']}")
        lines.append("spec:")
        lines.append(f"  topicName: {e['name']}")
        if helm:
            lines.append(f"  partitions: {{{{ max 1 (int (mulf {parts} $pf)) }}}}")
            lines.append("  replicas: {{ $rf }}")
        else:
            lines.append(f"  partitions: {parts}")
            lines.append(f"  replicas: {e['replication']}")
        lines.append("  config:")
        lines.append(f"    retention.ms: \"{e['retention_days'] * DAY_MS}\"")
        lines.append(f"    min.insync.replicas: \"{'{{ $minIsr }}' if helm else e['min_isr']}\"")
        lines.append("    cleanup.policy: delete")
        lines.append("    compression.type: producer")
        if e["class"] == "audit":
            lines.append("    message.timestamp.type: LogAppendTime")
        lines.append(f"    max.message.bytes: \"{'1048576' if e['class'] != 'ingest' else '4194304'}\"")
    if helm:
        lines.append("{{- end }}")
    return "\n".join(lines) + "\n"


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--topics", type=pathlib.Path, default=DEFAULT_TOPICS)
    ap.add_argument("-o", "--out", type=pathlib.Path, default=DEFAULT_OUT)
    ap.add_argument("--plain", action="store_true", help="YAML puro, sem diretivas Helm")
    ap.add_argument("--check", action="store_true", help="não escreve; falha se saída difere do arquivo")
    args = ap.parse_args()

    defaults, topics, retry = load(args.topics)
    entries = topic_entries(defaults, topics, retry)
    content = render(entries, helm=not args.plain)

    if args.check:
        current = args.out.read_text(encoding="utf-8") if args.out.exists() else ""
        if current != content:
            print(f"DESATUALIZADO: {args.out} — rode scripts/gen-kafka-topics.py", file=sys.stderr)
            return 1
        print(f"ok: {args.out} atualizado ({len(entries)} tópicos)")
        return 0

    args.out.parent.mkdir(parents=True, exist_ok=True)
    args.out.write_text(content, encoding="utf-8")
    print(f"gerado {args.out} ({len(entries)} KafkaTopic, incluindo retry)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
