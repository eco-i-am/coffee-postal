# Multi-arch (amd64 on the DigitalOcean Droplet, also fine on arm64 / dev machines).
FROM eclipse-temurin:21-jdk AS build
WORKDIR /src
COPY gradlew settings.gradle build.gradle ./
COPY gradle gradle
RUN ./gradlew --no-daemon dependencies > /dev/null 2>&1 || true
COPY src src
RUN ./gradlew --no-daemon bootJar -x test

FROM eclipse-temurin:21-jre
RUN apt-get update && apt-get install -y --no-install-recommends curl && rm -rf /var/lib/apt/lists/* \
 && useradd --system --uid 10001 pos
WORKDIR /app
COPY --from=build /src/build/libs/*.jar app.jar
USER pos
ENV JAVA_TOOL_OPTIONS="-Xmx512m -XX:MaxMetaspaceSize=192m -XX:+UseG1GC"
EXPOSE 8080
HEALTHCHECK --interval=30s --timeout=5s --start-period=60s \
  CMD curl -fsS http://localhost:8080/actuator/health || exit 1
ENTRYPOINT ["java","-jar","/app/app.jar"]
