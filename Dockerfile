# Etapa 1: compilación con Maven y JDK 17 (java.version del pom.xml)
FROM maven:3.9.9-eclipse-temurin-17 AS builder
WORKDIR /app

# Descarga las dependencias primero para aprovechar la caché de capas
COPY pom.xml .
RUN mvn -B dependency:go-offline

COPY src ./src
RUN mvn -B clean package -DskipTests

# Etapa 2: ejecución solo con el JRE 17
FROM eclipse-temurin:17-jre-alpine
WORKDIR /app
RUN addgroup -S ansertech && adduser -S ansertech -G ansertech
COPY --from=builder /app/target/*.jar app.jar
RUN mkdir -p /app/storage/quotations && chown -R ansertech:ansertech /app
USER ansertech
EXPOSE 8080
ENTRYPOINT ["java", "-Djava.security.egd=file:/dev/./urandom", "-jar", "app.jar"]
