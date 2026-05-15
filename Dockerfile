# ═══════════════════════════════════════════════════════════════════
# STAGE 1: Build the Java backend (Java 21)
# ═══════════════════════════════════════════════════════════════════
FROM maven:3.9.6-eclipse-temurin-21 AS java-build
WORKDIR /app
COPY pom.xml .
COPY src ./src
RUN mvn clean package -DskipTests

# ═══════════════════════════════════════════════════════════════════
# STAGE 2: Build the Next.js frontend (static export)
# ═══════════════════════════════════════════════════════════════════
FROM node:18-alpine AS ui-build
WORKDIR /app/ui
COPY ui/package*.json ./
RUN npm ci
COPY ui/ ./

# Clear any stale env vars so the frontend uses relative paths
ENV NEXT_PUBLIC_API_URL=""
ENV NEXT_PUBLIC_WS_URL=""
ENV DOCKER_BUILD="true"

RUN npm run build

# ═══════════════════════════════════════════════════════════════════
# STAGE 3: Production runtime (Java 21 + Nginx)
# ═══════════════════════════════════════════════════════════════════
FROM eclipse-temurin:21-jre-jammy

# Install Nginx and curl (for health checks)
RUN apt-get update && apt-get install -y nginx curl && rm -rf /var/lib/apt/lists/*

WORKDIR /app

# Copy the built Java JAR
COPY --from=java-build /app/target/p2p-1.0-SNAPSHOT-shaded.jar app.jar

# Copy the Next.js standalone build
COPY --from=ui-build /app/ui/.next/standalone ./ui-server
COPY --from=ui-build /app/ui/.next/static ./ui-static/_next/static
COPY --from=ui-build /app/ui/public ./ui-static

# Copy Nginx config and startup script
COPY nginx.conf /etc/nginx/nginx.conf
COPY start.sh /app/start.sh
RUN chmod +x /app/start.sh

# Expose the single port (Railway/Render will set PORT env var)
EXPOSE 3000

ENTRYPOINT ["/app/start.sh"]
