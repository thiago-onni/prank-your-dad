# Kafka Connect LOCAL do lakehouse (perfil compose `analytics`): imagem Debezium Connect + Apache Iceberg sink.
# Em Kubernetes o mesmo plugin vem na imagem Strimzi platform/images/kafka-connect-debezium (cluster sus-connect).
ARG DEBEZIUM_CONNECT_IMAGE=quay.io/debezium/connect:3.0.0.Final

FROM eclipse-temurin:17-jdk AS iceberg-sink
ARG ICEBERG_VERSION=1.9.2
WORKDIR /src
RUN set -eux; \
    apt-get update && apt-get install -y --no-install-recommends curl unzip && rm -rf /var/lib/apt/lists/*; \
    curl -fsSL "https://archive.apache.org/dist/iceberg/apache-iceberg-${ICEBERG_VERSION}/apache-iceberg-${ICEBERG_VERSION}.tar.gz" | tar xz --strip-components=1; \
    ./gradlew --no-daemon -x test -x integrationTest :iceberg-kafka-connect:iceberg-kafka-connect-runtime:distZip; \
    mkdir -p /out && unzip -q kafka-connect/kafka-connect-runtime/build/distributions/iceberg-kafka-connect-runtime-*.zip -d /out; \
    mv /out/iceberg-kafka-connect-runtime-* /out/iceberg-sink

FROM ${DEBEZIUM_CONNECT_IMAGE}
ARG POSTGRES_JDBC_VERSION=42.7.4
COPY --from=iceberg-sink --chown=1001:0 /out/iceberg-sink /kafka/connect/iceberg-sink
RUN curl -fsSL -o "/kafka/connect/iceberg-sink/lib/postgresql-${POSTGRES_JDBC_VERSION}.jar" \
      "https://repo1.maven.org/maven2/org/postgresql/postgresql/${POSTGRES_JDBC_VERSION}/postgresql-${POSTGRES_JDBC_VERSION}.jar"
