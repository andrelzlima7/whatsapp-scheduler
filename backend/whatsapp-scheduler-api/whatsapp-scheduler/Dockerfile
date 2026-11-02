FROM eclipse-temurin:21-jdk-jammy AS build
WORKDIR /app
COPY .mvn .mvn
COPY mvnw pom.xml ./
RUN chmod +x mvnw && ./mvnw -B -ntp dependency:go-offline
COPY src src
RUN ./mvnw -B -ntp -Dmaven.test.skip=true package

FROM eclipse-temurin:21-jre-jammy
WORKDIR /app
RUN groupadd --system app && useradd --system --gid app app && mkdir logs && chown app:app logs
COPY --from=build /app/target/whatsapp-scheduler-0.0.1-SNAPSHOT.jar app.jar
ENV SERVER_ADDRESS=0.0.0.0 JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=60 -XX:+UseSerialGC"
USER app
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
