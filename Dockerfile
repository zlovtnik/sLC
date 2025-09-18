# Use official Azul Zulu OpenJDK 21 JRE headless image for smaller footprint
FROM azul/zulu-openjdk:21-jre-headless

# Set working directory
WORKDIR /app

# Copy the built JAR file
COPY target/scala-2.13/health-check-log-aggregator_2.13-0.1.0.jar app.jar

# Expose the app port (must match application.conf)
EXPOSE 8080

# Create non-root user and drop privileges
RUN useradd -ms /sbin/nologin appuser && chown -R appuser /app
USER appuser

# Optional: make the JVM container-aware and fail-fast on OOM
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75.0 -XX:+ExitOnOutOfMemoryError"

# Basic container health check (ensure curl/wget is available or swap to busybox wget)
HEALTHCHECK --interval=30s --timeout=3s --start-period=10s --retries=3 \
  CMD wget -qO- http://localhost:8080/health || exit 1

# Run the application
CMD ["java", "-jar", "app.jar"]