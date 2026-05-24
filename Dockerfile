# syntax=docker/dockerfile:1
#
# MealPrep AI application image. Used by the prod-parity E2E docker-compose
# stack (e2e/docker-compose.yml) and suitable as the base for a real deploy.
#
# Two stages:
#   1. build  — compile + package the Spring Boot fat jar with the committed
#               Maven wrapper. Tests are skipped here: the jar is an artefact;
#               correctness is gated by the separate `mvn verify` CI job.
#   2. runtime — slim JRE running the jar as a non-root user.
#
# The active Spring profile is supplied at run time via SPRING_PROFILES_ACTIVE
# (the E2E stack sets it to `e2e`); the image itself is profile-agnostic.

# ---------- build stage ----------
FROM eclipse-temurin:17-jdk AS build
WORKDIR /workspace

# The committed Maven wrapper downloads + unzips Maven on first use; the JDK
# base image ships no `unzip`, so install it (clearing apt lists to stay slim).
RUN apt-get update && apt-get install -y --no-install-recommends unzip \
    && rm -rf /var/lib/apt/lists/*

# Warm the dependency cache on its own layer so source-only changes don't
# re-download the world.
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
RUN ./mvnw --batch-mode --no-transfer-progress dependency:go-offline

# Now the sources. lld/prompts are mirrored onto the classpath by the build
# (see pom.xml <resources>), so copy them too.
COPY src/ src/
COPY lld/ lld/
RUN ./mvnw --batch-mode --no-transfer-progress -DskipTests clean package \
    && cp target/*.jar /workspace/app.jar

# ---------- runtime stage ----------
FROM eclipse-temurin:17-jre AS runtime
WORKDIR /app

# Run as an unprivileged user.
RUN groupadd --system mealprep && useradd --system --gid mealprep --no-create-home mealprep
USER mealprep

COPY --from=build /workspace/app.jar /app/app.jar

EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
