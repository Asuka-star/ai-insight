# ============================================================
# Stage 1: Build frontend
# ============================================================
FROM node:18-alpine AS frontend-builder

WORKDIR /build/frontend
COPY frontend/package.json frontend/package-lock.json* ./
RUN npm ci --prefer-offline
COPY frontend/ ./
RUN npm run build

# ============================================================
# Stage 2: Build backend
# ============================================================
FROM maven:3.9-eclipse-temurin-17 AS backend-builder

WORKDIR /build

# Cache Maven dependencies first
COPY pom.xml .
RUN mvn dependency:go-offline -B -q

# Copy source and inject frontend build into Spring Boot static resources
COPY src/ src/
COPY --from=frontend-builder /build/frontend/dist/ src/main/resources/static/

RUN mvn package -DskipTests -B -q

# ============================================================
# Stage 3: Runtime
# ============================================================
FROM eclipse-temurin:17-jre AS runtime

# System dependencies for Playwright Chromium + curl for health check
# (set AI_INSIGHT_WEB_RENDERER_ENABLED=false to skip rendering)
RUN apt-get update && apt-get install -y --no-install-recommends \
        curl \
        libnss3 libatk1.0-0 libatk-bridge2.0-0 libcups2 libdrm2 \
        libxkbcommon0 libxcomposite1 libxdamage1 libxrandr2 \
        libgbm1 libpango-1.0-0 libcairo2 libasound2 libxshmfence1 \
        fonts-wqy-zenhei fonts-noto-cjk \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /app

# Copy the Spring Boot fat jar
COPY --from=backend-builder /build/target/*.jar app.jar

# Expose HTTP port
EXPOSE 8080

# Health check via Spring Boot Actuator
HEALTHCHECK --interval=30s --timeout=5s --start-period=60s --retries=3 \
    CMD curl -sf http://localhost:8080/actuator/health || exit 1

# Default environment variables
ENV SPRING_PROFILES_ACTIVE=docker \
    POSTGRES_URL=jdbc:postgresql://postgres:5432/ai_insight \
    POSTGRES_USER=ai_insight \
    POSTGRES_PASSWORD=ai_insight \
    LOGGING_CHARSET_CONSOLE=UTF-8 \
    AI_INSIGHT_WEB_RENDERER_ENABLED=true \
    AI_INSIGHT_WEB_RENDERER_HEADLESS=true \
    AI_INSIGHT_WEB_RENDERER_BROWSER_CHANNEL=chromium \
    JAVA_OPTS="-Xms512m -Xmx2g -XX:+UseG1GC"

ENTRYPOINT ["sh", "-c", "java ${JAVA_OPTS} -jar app.jar"]
