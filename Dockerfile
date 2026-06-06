FROM eclipse-temurin:17-jdk-alpine AS builder
WORKDIR /app
COPY pom.xml .
COPY src ./src
RUN apk add --no-cache maven && mvn clean package -DskipTests

FROM eclipse-temurin:17-jre-alpine
WORKDIR /app
RUN addgroup -S ansertech && adduser -S ansertech -G ansertech
COPY --from=builder /app/target/*.jar app.jar
RUN mkdir -p /app/storage/quotations && chown -R ansertech:ansertech /app
USER ansertech
EXPOSE 8080
ENTRYPOINT ["java", "-Djava.security.egd=file:/dev/./urandom", "-jar", "app.jar"]
