FROM confluentinc/cp-kafka:latest

WORKDIR /app

# Copy the initialization script with executable permissions
COPY --chmod=755 init-topics.sh /app/init-topics.sh

# Run the script
ENTRYPOINT ["/app/init-topics.sh"]
