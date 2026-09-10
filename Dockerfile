FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build
COPY pom.xml .
COPY src ./src
RUN mvn -q -DskipTests package
FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /build/target/loxone-events.jar /app/loxone-events.jar
USER 1026:1026
ENTRYPOINT ["java","-Xms32m","-Xmx256m","-jar","/app/loxone-events.jar","/run/loxone/config.json"]
