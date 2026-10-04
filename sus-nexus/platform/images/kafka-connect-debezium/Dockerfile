# Imagem Kafka Connect para Strimzi com:
#   - Debezium PostgreSQL (+ Outbox Event Router, parte do core do Debezium) — CDC do outbox do core;
#   - converter Apicurio;
#   - Apache Iceberg Kafka Connect sink (runtime) + driver JDBC PostgreSQL — ingestão dos tópicos de domínio
#     sus.* nas tabelas bronze do lakehouse (Fase 4, ADR-015; catálogo JDBC no PostgreSQL, dados no MinIO).
# Construída/assinada pelo workflow sus-nexus-release. Referenciada em platform/helm/sus-nexus/values.yaml
# (kafkaConnect.image).
#
# O runtime do Iceberg sink não é publicado como artefato binário pelo projeto Apache Iceberg: é construído a
# partir do pacote-fonte oficial (com version.txt) no estágio `iceberg-sink` abaixo.
ARG STRIMZI_VERSION=0.45.0
ARG KAFKA_VERSION=3.9.0

FROM eclipse-temurin:17-jdk AS iceberg-sink
ARG ICEBERG_VERSION=1.9.2
WORKDIR /src
RUN set -eux; \
    apt-get update && apt-get install -y --no-install-recommends curl unzip && rm -rf /var/lib/apt/lists/*; \
    curl -fsSL "https://archive.apache.org/dist/iceberg/apache-iceberg-${ICEBERG_VERSION}/apache-iceberg-${ICEBERG_VERSION}.tar.gz" | tar xz --strip-components=1; \
    ./gradlew --no-daemon -x test -x integrationTest :iceberg-kafka-connect:iceberg-kafka-connect-runtime:distZip; \
    mkdir -p /out && unzip -q kafka-connect/kafka-connect-runtime/build/distributions/iceberg-kafka-connect-runtime-*.zip -d /out; \
    mv /out/iceberg-kafka-connect-runtime-* /out/iceberg-sink

FROM quay.io/strimzi/kafka:${STRIMZI_VERSION}-kafka-${KAFKA_VERSION}

ARG DEBEZIUM_VERSION=3.0.0.Final
ARG APICURIO_VERSION=2.6.5.Final
ARG POSTGRES_JDBC_VERSION=42.7.4

USER root:root
COPY --from=iceberg-sink /out/iceberg-sink /opt/kafka/plugins/iceberg-sink
RUN set -eux; \
    mkdir -p /opt/kafka/plugins/debezium-postgres /opt/kafka/plugins/apicurio-converter; \
    cd /opt/kafka/plugins/debezium-postgres; \
    curl -fsSL "https://repo1.maven.org/maven2/io/debezium/debezium-connector-postgres/${DEBEZIUM_VERSION}/debezium-connector-postgres-${DEBEZIUM_VERSION}-plugin.tar.gz" | tar xz --strip-components=1; \
    cd /opt/kafka/plugins/apicurio-converter; \
    curl -fsSL "https://repo1.maven.org/maven2/io/apicurio/apicurio-registry-distro-connect-converter/${APICURIO_VERSION}/apicurio-registry-distro-connect-converter-${APICURIO_VERSION}.tar.gz" | tar xz; \
    curl -fsSL -o "/opt/kafka/plugins/iceberg-sink/lib/postgresql-${POSTGRES_JDBC_VERSION}.jar" \
      "https://repo1.maven.org/maven2/org/postgresql/postgresql/${POSTGRES_JDBC_VERSION}/postgresql-${POSTGRES_JDBC_VERSION}.jar"; \
    chown -R 1001:0 /opt/kafka/plugins; chmod -R g+rX /opt/kafka/plugins
USER 1001

LABEL org.opencontainers.image.title="sus-nexus/kafka-connect-debezium" \
      org.opencontainers.image.description="Strimzi Kafka Connect + Debezium PostgreSQL (outbox) + Apicurio converter + Apache Iceberg sink (lakehouse)" \
      org.opencontainers.image.vendor="Secretaria Municipal de Saude"
