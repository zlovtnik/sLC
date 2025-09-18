# Use Azul Zulu JRE 21 slim image for smaller footprint
FROM azulzulu:21-jre-slim

# Set working directory
WORKDIR /app

# Copy the built JAR file
COPY target/scala-2.13/health-check-log-aggregator_2.13-0.1.0.jar app.jar

# Expose the health check port
EXPOSE 9090

# Set the default command to run the application
CMD ["java", "-jar", "app.jar"]