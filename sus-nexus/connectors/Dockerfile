# Build genérico de um conector do reactor multi-módulo. Contexto: sus-nexus/connectors.
#   docker build --build-arg CONNECTOR_MODULE=connector-pec --build-arg PORT=8092 -t sus-nexus/connector-pec .
# Portas: connector-terminology 8090, connector-cnes 8091, connector-pec 8092,
#         connector-sisreg 8093, connector-esus-regulacao 8094, connector-lis 8095 (MLLP 2575).
FROM maven:3.9.11-eclipse-temurin-21 AS build
ARG CONNECTOR_MODULE=connector-pec
WORKDIR /workspace
COPY pom.xml .
COPY connector-sdk/pom.xml connector-sdk/
COPY connector-template/pom.xml connector-template/
COPY connector-terminology/pom.xml connector-terminology/
COPY connector-cnes/pom.xml connector-cnes/
COPY connector-pec/pom.xml connector-pec/
COPY connector-sisreg/pom.xml connector-sisreg/
COPY connector-esus-regulacao/pom.xml connector-esus-regulacao/
COPY connector-lis/pom.xml connector-lis/
RUN mvn -q -B -pl ${CONNECTOR_MODULE} -am dependency:go-offline -Dfmt.skip || true
COPY . .
RUN mvn -q -B -pl ${CONNECTOR_MODULE} -am package -DskipTests -Dfmt.skip \
    && cp -r ${CONNECTOR_MODULE}/target/quarkus-app /workspace/quarkus-app

FROM eclipse-temurin:21-jre-jammy
ARG PORT=8092
ENV LANG=C.UTF-8 TZ=America/Sao_Paulo PORT=${PORT} \
    JAVA_OPTS="-XX:MaxRAMPercentage=75 -Djava.util.logging.manager=org.jboss.logmanager.LogManager"
RUN groupadd --system --gid 1001 sus && useradd --system --uid 1001 --gid sus --home /app sus \
    && mkdir -p /app/data && chown -R sus:sus /app
WORKDIR /app
COPY --from=build --chown=sus:sus /workspace/quarkus-app/lib/ /app/lib/
COPY --from=build --chown=sus:sus /workspace/quarkus-app/*.jar /app/
COPY --from=build --chown=sus:sus /workspace/quarkus-app/app/ /app/app/
COPY --from=build --chown=sus:sus /workspace/quarkus-app/quarkus/ /app/quarkus/
USER 1001
VOLUME ["/app/data"]
EXPOSE ${PORT}
HEALTHCHECK --interval=30s --timeout=5s --retries=3 CMD ["sh", "-c", "wget -qO- http://localhost:${PORT}/q/health/ready | grep -q UP"]
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -Dquarkus.http.port=${PORT} -jar /app/quarkus-run.jar"]
