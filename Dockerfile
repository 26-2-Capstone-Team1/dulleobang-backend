FROM eclipse-temurin:21-jdk AS builder
WORKDIR /workspace
COPY . .
RUN bash gradlew bootJar --no-daemon
FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=builder /workspace/build/libs/dulleobang-backend-v2-2.0.0-p7.jar app.jar
EXPOSE 8081
ENTRYPOINT ["java","-jar","/app/app.jar"]
