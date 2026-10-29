FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /build
COPY pom.xml .
COPY duomaomao-spring/pom.xml duomaomao-spring/pom.xml
COPY backend/pom.xml backend/pom.xml
COPY duomaomao-spring/src duomaomao-spring/src
COPY backend/src backend/src
RUN mvn -B -ntp -pl backend -am -DskipTests package

FROM eclipse-temurin:17-jre
WORKDIR /app
RUN useradd --system --uid 10001 app && mkdir -p /app/data/imports /app/data/snapshots && chown -R app:app /app
COPY --from=build /build/backend/target/jinjing2-backend-0.1.0.jar /app/app.jar
USER app
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
