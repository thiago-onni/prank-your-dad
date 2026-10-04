# Build multi-stage. Contexto: raiz de sus-nexus/connectors (projeto multi-módulo).
#   docker build -f connector-pec/src/main/docker/Dockerfile -t sus-nexus/connector-pec .
FROM maven:3.9.11-eclipse-temurin-21 AS build
WORKDIR /workspace
COPY pom.xml .
COPY connector-sdk/pom.xml connector-sdk/
COPY connector-template/pom.xml connector-template/
COPY connector-terminology/pom.xml connector-terminology/
COPY connector-cnes/pom.xml connector-cnes/
COPY connector-pec/pom.xml connector-pec/
RUN mvn -q -B -pl connector-pec -am dependency:go-offline -Dfmt.skip || true
COPY . .
RUN mvn -q -B -pl connector-pec -am package -DskipTests -Dfmt.skip

FROM eclipse-temurin:21-jre-jammy
ENV LANG=C.UTF-8 TZ=America/Sao_Paulo \
    JAVA_OPTS="-XX:MaxRAMPercentage=75 -Djava.util.logging.manager=org.jboss.logmanager.LogManager"
RUN groupadd --system --gid 1001 sus && useradd --system --uid 1001 --gid sus --home /app sus \
    && mkdir -p /app/data && chown -R sus:sus /app
WORKDIR /app
COPY --from=build --chown=sus:sus /workspace/connector-pec/target/quarkus-app/lib/ /app/lib/
COPY --from=build --chown=sus:sus /workspace/connector-pec/target/quarkus-app/*.jar /app/
COPY --from=build --chown=sus:sus /workspace/connector-pec/target/quarkus-app/app/ /app/app/
COPY --from=build --chown=sus:sus /workspace/connector-pec/target/quarkus-app/quarkus/ /app/quarkus/
USER 1001
VOLUME ["/app/data"]
EXPOSE 8092
HEALTHCHECK --interval=30s --timeout=5s --retries=3 CMD ["sh", "-c", "wget -qO- http://localhost:8092/q/health/ready | grep -q UP"]
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/quarkus-run.jar"]
