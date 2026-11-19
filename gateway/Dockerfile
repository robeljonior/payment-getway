FROM eclipse-temurin:21-jdk AS build
WORKDIR /src
COPY . .
RUN ./mvnw -q -DskipTests package

FROM eclipse-temurin:21-jre
COPY --from=build /src/target/payment-gateway-0.1.0-SNAPSHOT.jar /app.jar
USER 1000
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app.jar"]
