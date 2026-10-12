FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /app
COPY pom.xml .
RUN mvn -B -q dependency:go-offline
COPY src src
RUN mvn -B -q package -DskipTests

FROM eclipse-temurin:21-jre
# git is used to read commit / author metadata from the mounted repository
RUN apt-get update && apt-get install -y --no-install-recommends git && rm -rf /var/lib/apt/lists/* \
    && git config --system --add safe.directory '*'
WORKDIR /app
COPY --from=build /app/target/gitcontext-*.jar app.jar
ENV REPO_PATH=/repo
EXPOSE 8080
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "app.jar"]
