FROM amazoncorretto:21-alpine

WORKDIR /app

# Copy the JAR file
COPY target/order-producer-1.0.0.jar app.jar

# Run as non-root user
RUN addgroup -S appgroup && adduser -S appuser -G appgroup
USER appuser

# Expose actuator port (if needed)
EXPOSE 8080

# Run the application
ENTRYPOINT ["java", "-jar", "app.jar"]
