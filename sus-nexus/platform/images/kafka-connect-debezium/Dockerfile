# Imagem Kafka Connect para Strimzi com o conector Debezium PostgreSQL (+ Outbox Event Router, que faz
# parte do core do Debezium) e o converter Apicurio. Construída/assinada pelo workflow sus-nexus-release.
# Referenciada em platform/helm/sus-nexus/values.yaml (kafkaConnect.image).
ARG STRIMZI_VERSION=0.45.0
ARG KAFKA_VERSION=3.9.0
FROM quay.io/strimzi/kafka:${STRIMZI_VERSION}-kafka-${KAFKA_VERSION}

ARG DEBEZIUM_VERSION=3.0.0.Final
ARG APICURIO_VERSION=2.6.5.Final

USER root:root
RUN set -eux; \
    mkdir -p /opt/kafka/plugins/debezium-postgres /opt/kafka/plugins/apicurio-converter; \
    cd /opt/kafka/plugins/debezium-postgres; \
    curl -fsSL "https://repo1.maven.org/maven2/io/debezium/debezium-connector-postgres/${DEBEZIUM_VERSION}/debezium-connector-postgres-${DEBEZIUM_VERSION}-plugin.tar.gz" | tar xz --strip-components=1; \
    cd /opt/kafka/plugins/apicurio-converter; \
    curl -fsSL "https://repo1.maven.org/maven2/io/apicurio/apicurio-registry-distro-connect-converter/${APICURIO_VERSION}/apicurio-registry-distro-connect-converter-${APICURIO_VERSION}.tar.gz" | tar xz; \
    chown -R 1001:0 /opt/kafka/plugins; chmod -R g+rX /opt/kafka/plugins
USER 1001

LABEL org.opencontainers.image.title="sus-nexus/kafka-connect-debezium" \
      org.opencontainers.image.description="Strimzi Kafka Connect + Debezium PostgreSQL (outbox) + Apicurio converter" \
      org.opencontainers.image.vendor="Secretaria Municipal de Saude"
