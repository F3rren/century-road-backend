# ---- Dev stage: mounts source at runtime, runs with hot-reload ----
FROM eclipse-temurin:21-jdk AS dev
WORKDIR /app
RUN apt-get update && apt-get install -y --no-install-recommends curl && rm -rf /var/lib/apt/lists/*
EXPOSE 8080 5007
ENTRYPOINT ["./mvnw", "spring-boot:run", \
  "-Dspring-boot.run.jvmArguments=-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=*:5007"]

# ---- Build stage ----
FROM eclipse-temurin:21-jdk AS build
WORKDIR /app
COPY .mvn .mvn
COPY mvnw pom.xml ./
RUN chmod +x mvnw && ./mvnw dependency:go-offline -q
COPY src ./src
RUN ./mvnw package -DskipTests -q

# ---- Production stage ----
FROM eclipse-temurin:21-jre AS prod
WORKDIR /app
# curl exists for the Compose healthcheck only; the JRE image is not guaranteed to ship it.
RUN apt-get update && apt-get install -y --no-install-recommends curl && rm -rf /var/lib/apt/lists/* \
  && useradd --system --uid 10001 --no-create-home app
COPY --from=build --chown=app:app /app/target/*.jar app.jar
USER app
# Documents the default only: the port actually used is GATEWAY_PORT, which Compose passes in.
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "-Dspring.profiles.active=prod", "app.jar"]
