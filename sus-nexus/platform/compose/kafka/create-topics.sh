#!/usr/bin/env bash
# Cria (idempotente) os tópicos Kafka declarados em contracts/events/topics.yaml.
#
# Uso:
#   ./create-topics.sh                           # host: usa `docker compose exec kafka` por padrão
#   KAFKA_BOOTSTRAP_SERVERS=kafka:19092 ./create-topics.sh   # dentro do container (serviço kafka-init)
#
# Variáveis:
#   TOPICS_FILE              caminho do topics.yaml (default: ../../../contracts/events/topics.yaml)
#   KAFKA_BOOTSTRAP_SERVERS  default localhost:9092
#   KAFKA_TOPICS_CMD         comando kafka-topics (default: autodetectado)
#   REPLICATION_FACTOR       sobrescreve `defaults.replication` (local = 1)
#   MIN_INSYNC_REPLICAS      sobrescreve `defaults.min_insync_replicas` (local = 1)
#   CREATE_RETRY_TOPICS      true|false — cria `<tópico>.retry.1..3` para classes ingest/domain (default true)
#   DRY_RUN                  true → apenas imprime os comandos
#
# Parser: usa `yq` (mikefarah) se existir, senão `python3` + PyYAML, senão um parser awk que entende o
# formato regular do topics.yaml (defaults + lista `- name:` com chaves escalares por linha).
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
TOPICS_FILE="${TOPICS_FILE:-${SCRIPT_DIR}/../../../contracts/events/topics.yaml}"
BOOTSTRAP="${KAFKA_BOOTSTRAP_SERVERS:-localhost:9092}"
CREATE_RETRY_TOPICS="${CREATE_RETRY_TOPICS:-true}"
DRY_RUN="${DRY_RUN:-false}"

if [[ ! -f "$TOPICS_FILE" ]]; then
  echo "topics.yaml não encontrado em ${TOPICS_FILE}" >&2
  exit 1
fi

if [[ -z "${KAFKA_TOPICS_CMD:-}" ]]; then
  if [[ -x /opt/kafka/bin/kafka-topics.sh ]]; then
    KAFKA_TOPICS_CMD="/opt/kafka/bin/kafka-topics.sh"
  elif command -v kafka-topics.sh >/dev/null 2>&1; then
    KAFKA_TOPICS_CMD="kafka-topics.sh"
  elif command -v kafka-topics >/dev/null 2>&1; then
    KAFKA_TOPICS_CMD="kafka-topics"
  else
    KAFKA_TOPICS_CMD="docker compose exec -T kafka /opt/kafka/bin/kafka-topics.sh"
    BOOTSTRAP="${KAFKA_BOOTSTRAP_SERVERS:-kafka:19092}"
  fi
fi

# ---------------------------------------------------------------------------
# Emite linhas TSV: name<TAB>class<TAB>partitions<TAB>replication<TAB>min_isr<TAB>retention_days<TAB>compat
# ---------------------------------------------------------------------------
emit_topics() {
  if command -v yq >/dev/null 2>&1 && yq --version 2>/dev/null | grep -qi mikefarah; then
    yq -r '
      .defaults as $d
      | .topics[]
      | [ .name,
          (.class // "domain"),
          (.partitions // $d.partitions),
          (.replication // $d.replication),
          (.min_insync_replicas // $d.min_insync_replicas),
          (.retention_days // $d.retention_days),
          (.schema_compatibility // $d.schema_compatibility) ]
      | @tsv' "$TOPICS_FILE"
  elif command -v python3 >/dev/null 2>&1 && python3 -c 'import yaml' 2>/dev/null; then
    python3 - "$TOPICS_FILE" <<'PY'
import sys, yaml
doc = yaml.safe_load(open(sys.argv[1], encoding="utf-8"))
d = doc.get("defaults", {})
for t in doc["topics"]:
    print("\t".join(str(x) for x in [
        t["name"], t.get("class", "domain"),
        t.get("partitions", d.get("partitions", 12)),
        t.get("replication", d.get("replication", 3)),
        t.get("min_insync_replicas", d.get("min_insync_replicas", 2)),
        t.get("retention_days", d.get("retention_days", 30)),
        t.get("schema_compatibility", d.get("schema_compatibility", "BACKWARD")),
    ]))
PY
  else
    # Parser awk (POSIX, funciona no busybox/alpine) para o formato regular do topics.yaml.
    awk '
      function trim(s) { sub(/^[ \t]+/, "", s); sub(/[ \t]+$/, "", s); return s }
      function kv(line,   i, k, v) {
        # "  chave: valor   # comentário" -> k="chave", v="valor"
        sub(/#.*$/, "", line)
        i = index(line, ":"); if (i == 0) return 0
        k = trim(substr(line, 1, i - 1)); v = trim(substr(line, i + 1))
        sub(/^-[ \t]+/, "", k)
        KEY = k; VAL = v; return 1
      }
      function flush() {
        if (name != "") printf "%s\t%s\t%s\t%s\t%s\t%s\t%s\n", name, cls, parts, repl, isr, ret, compat
        name = ""; cls = "domain"; parts = d["partitions"]; repl = d["replication"]
        isr = d["min_insync_replicas"]; ret = d["retention_days"]; compat = d["schema_compatibility"]
      }
      /^[ \t]*#/ || /^[ \t]*$/ { next }
      /^defaults:/      { section = "defaults"; next }
      /^topics:/        { section = "topics"; flush(); next }
      /^retry_policy:/  { section = "retry"; flush(); next }
      section == "defaults" && kv($0) { d[KEY] = VAL; next }
      section == "topics" && $0 ~ /^[ \t]*-[ \t]+name:/ { flush(); kv($0); name = VAL; next }
      section == "topics" && kv($0) {
        if (KEY == "class") cls = VAL
        else if (KEY == "partitions") parts = VAL
        else if (KEY == "replication") repl = VAL
        else if (KEY == "min_insync_replicas") isr = VAL
        else if (KEY == "retention_days") ret = VAL
        else if (KEY == "schema_compatibility") compat = VAL
        next
      }
      END { flush() }
    ' "$TOPICS_FILE"
  fi
}

run() {
  if [[ "$DRY_RUN" == "true" ]]; then
    echo "+ $*"
  else
    # shellcheck disable=SC2086
    $KAFKA_TOPICS_CMD "$@"
  fi
}

create_topic() {
  local name="$1" partitions="$2" replication="$3" min_isr="$4" retention_days="$5" extra="${6:-}"
  local retention_ms=$(( retention_days * 24 * 60 * 60 * 1000 ))
  echo ">> ${name} (partitions=${partitions} rf=${replication} min.isr=${min_isr} retention=${retention_days}d)"
  # shellcheck disable=SC2086
  run --bootstrap-server "$BOOTSTRAP" --create --if-not-exists \
    --topic "$name" --partitions "$partitions" --replication-factor "$replication" \
    --config "retention.ms=${retention_ms}" \
    --config "min.insync.replicas=${min_isr}" \
    --config "cleanup.policy=delete" \
    --config "compression.type=producer" \
    $extra
}

echo "Criando tópicos de ${TOPICS_FILE} em ${BOOTSTRAP} ..."
created=0
while IFS=$'\t' read -r name cls partitions replication min_isr retention_days compat; do
  [[ -z "$name" ]] && continue
  replication="${REPLICATION_FACTOR:-$replication}"
  min_isr="${MIN_INSYNC_REPLICAS:-$min_isr}"
  create_topic "$name" "$partitions" "$replication" "$min_isr" "$retention_days"
  created=$((created + 1))
  if [[ "$CREATE_RETRY_TOPICS" == "true" && ( "$cls" == "domain" || "$cls" == "ingest" ) ]]; then
    for n in 1 2 3; do
      create_topic "${name}.retry.${n}" "$partitions" "$replication" "$min_isr" 7
      created=$((created + 1))
    done
  fi
done < <(emit_topics)

echo "Concluído: ${created} tópicos processados (compatibilidade de schema é configurada no Apicurio, não no Kafka)."
