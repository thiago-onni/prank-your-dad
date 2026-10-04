# Build multi-stage do fhir-gateway (Quarkus, modo JVM). Contexto: sus-nexus/fhir-gateway.
#   docker build -t sus-nexus/fhir-gateway .
FROM maven:3.9.11-eclipse-temurin-21 AS build
WORKDIR /workspace
COPY pom.xml .
RUN mvn -q -B dependency:go-offline -Dfmt.skip || true
COPY src ./src
RUN mvn -q -B package -DskipTests -Dfmt.skip

FROM eclipse-temurin:21-jre-jammy
ENV LANG=C.UTF-8 TZ=America/Sao_Paulo \
    JAVA_OPTS="-XX:MaxRAMPercentage=75 -Djava.util.logging.manager=org.jboss.logmanager.LogManager"
RUN groupadd --system --gid 1001 sus && useradd --system --uid 1001 --gid sus --home /app sus \
    && mkdir -p /app && chown -R sus:sus /app
WORKDIR /app
COPY --from=build --chown=sus:sus /workspace/target/quarkus-app/lib/ /app/lib/
COPY --from=build --chown=sus:sus /workspace/target/quarkus-app/*.jar /app/
COPY --from=build --chown=sus:sus /workspace/target/quarkus-app/app/ /app/app/
COPY --from=build --chown=sus:sus /workspace/target/quarkus-app/quarkus/ /app/quarkus/
USER 1001
EXPOSE 8081
HEALTHCHECK --interval=30s --timeout=5s --retries=3 CMD ["sh", "-c", "wget -qO- http://localhost:8081/q/health/ready | grep -q UP"]
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/quarkus-run.jar"]
