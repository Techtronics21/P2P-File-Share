# STAGE 1: Build the application
FROM maven:3.9.6-eclipse-temurin-17 AS build
WORKDIR /app

# Copy pom.xml and source code
COPY pom.xml .
COPY src ./src

# Build the shaded (fat) JAR
RUN mvn clean package -DskipTests

# STAGE 2: Run the application
FROM eclipse-temurin:17-jre-jammy
WORKDIR /app

# Copy only the built JAR from the build stage
COPY --from=build /app/target/p2p-1.0-SNAPSHOT-shaded.jar app.jar

# Create a non-root user for security (Hardening!)
RUN useradd -m peerlink && chown -R peerlink:peerlink /app
USER peerlink

# Expose ports: 8080 (API) and 8081 (WebSocket)
EXPOSE 8080
EXPOSE 8081

# Run the app
ENTRYPOINT ["java", "-jar", "app.jar"]
