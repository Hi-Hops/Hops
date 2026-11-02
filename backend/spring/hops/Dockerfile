# syntax=docker/dockerfile:1
FROM eclipse-temurin:21-jdk AS build

WORKDIR /workspace
COPY gradlew settings.gradle.kts build.gradle.kts ./
COPY gradle/ ./gradle/
# Windows 체크아웃에서도 Wrapper를 Linux에서 실행할 수 있게 줄바꿈을 맞춘다.
RUN sed -i 's/\r$//' gradlew && chmod +x gradlew
COPY src/main/ ./src/main/
RUN --mount=type=cache,target=/root/.gradle \
    ./gradlew --no-daemon bootJar

FROM eclipse-temurin:21-jre AS runtime

RUN useradd --uid 10001 --no-create-home --shell /usr/sbin/nologin hops
WORKDIR /app
COPY --from=build --chown=hops:hops /workspace/build/libs/*.jar ./app.jar
USER hops

EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
