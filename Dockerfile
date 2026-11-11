FROM maven:3.9.11-eclipse-temurin-21 AS build
WORKDIR /build
COPY pom.xml ./
RUN --mount=type=cache,target=/root/.m2 mvn -B dependency:go-offline
COPY src ./src
COPY docs/contratos ./docs/contratos
RUN --mount=type=cache,target=/root/.m2 mvn -B verify

FROM eclipse-temurin:21-jre-alpine AS runtime
WORKDIR /app
RUN addgroup -S notificacoes && adduser -S -G notificacoes notificacoes
COPY --from=build --chown=notificacoes:notificacoes /build/target/ms-notificacoes-*.jar /app/app.jar
USER notificacoes
EXPOSE 8081
HEALTHCHECK --interval=10s --timeout=5s --start-period=60s --retries=6 \
    CMD wget -q -O /dev/null http://127.0.0.1:8081/actuator/health || exit 1
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
