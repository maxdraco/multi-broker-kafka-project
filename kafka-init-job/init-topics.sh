#!/bin/bash
set -e

# Read configuration from environment variables (set by ConfigMap)
BOOTSTRAP_SERVER="${KAFKA_BOOTSTRAP_SERVER:-kafka-service:9092}"

# Orders topic configuration
ORDERS_TOPIC="${ORDERS_TOPIC_NAME:-orders}"
ORDERS_PARTITIONS="${ORDERS_PARTITIONS:-3}"
ORDERS_REPLICATION="${ORDERS_REPLICATION_FACTOR:-3}"
ORDERS_RETENTION="${ORDERS_RETENTION_MS:-604800000}"
ORDERS_MIN_ISR="${ORDERS_MIN_INSYNC_REPLICAS:-2}"

# Orders-avro topic configuration
ORDERS_AVRO_TOPIC="${ORDERS_AVRO_TOPIC_NAME:-orders-avro}"
ORDERS_AVRO_PARTITIONS="${ORDERS_AVRO_PARTITIONS:-3}"
ORDERS_AVRO_REPLICATION="${ORDERS_AVRO_REPLICATION_FACTOR:-3}"
ORDERS_AVRO_RETENTION="${ORDERS_AVRO_RETENTION_MS:-604800000}"
ORDERS_AVRO_MIN_ISR="${ORDERS_AVRO_MIN_INSYNC_REPLICAS:-2}"

# Order-consumed topic configuration
CONSUMED_TOPIC="${CONSUMED_TOPIC_NAME:-order-consumed}"
CONSUMED_PARTITIONS="${CONSUMED_PARTITIONS:-3}"
CONSUMED_REPLICATION="${CONSUMED_REPLICATION_FACTOR:-3}"
CONSUMED_RETENTION="${CONSUMED_RETENTION_MS:-259200000}"
CONSUMED_MIN_ISR="${CONSUMED_MIN_INSYNC_REPLICAS:-2}"

# DLT topic configuration
DLT_TOPIC="${DLT_TOPIC_NAME:-orders.DLT}"
DLT_PARTITIONS="${DLT_PARTITIONS:-3}"
DLT_REPLICATION="${DLT_REPLICATION_FACTOR:-3}"
DLT_RETENTION="${DLT_RETENTION_MS:-2592000000}"
DLT_MIN_ISR="${DLT_MIN_INSYNC_REPLICAS:-2}"

echo "=========================================="
echo "Kafka Topic Initialization"
echo "Bootstrap Server: $BOOTSTRAP_SERVER"
echo "=========================================="

# Wait for Kafka to be ready
echo "Waiting for Kafka to be ready..."
max_attempts=30
attempt=0

while [ $attempt -lt $max_attempts ]; do
    if kafka-topics --bootstrap-server "$BOOTSTRAP_SERVER" --list >/dev/null 2>&1; then
        echo "Kafka is ready!"
        break
    fi
    attempt=$((attempt + 1))
    echo "Attempt $attempt/$max_attempts - Kafka not ready yet, waiting..."
    sleep 2
done

if [ $attempt -eq $max_attempts ]; then
    echo "ERROR: Kafka did not become ready in time"
    exit 1
fi

# Create topics
echo ""
echo "Creating topics..."
echo ""

# 1. Orders topic
echo "Creating '$ORDERS_TOPIC' topic..."
kafka-topics --bootstrap-server "$BOOTSTRAP_SERVER" \
    --create \
    --if-not-exists \
    --topic "$ORDERS_TOPIC" \
    --partitions "$ORDERS_PARTITIONS" \
    --replication-factor "$ORDERS_REPLICATION" \
    --config retention.ms="$ORDERS_RETENTION" \
    --config min.insync.replicas="$ORDERS_MIN_ISR" \
    --config cleanup.policy=delete

echo "✓ Topic '$ORDERS_TOPIC' created"

# 2. Orders-avro topic (Avro with Schema Registry)
echo ""
echo "Creating '$ORDERS_AVRO_TOPIC' topic..."
kafka-topics --bootstrap-server "$BOOTSTRAP_SERVER" \
    --create \
    --if-not-exists \
    --topic "$ORDERS_AVRO_TOPIC" \
    --partitions "$ORDERS_AVRO_PARTITIONS" \
    --replication-factor "$ORDERS_AVRO_REPLICATION" \
    --config retention.ms="$ORDERS_AVRO_RETENTION" \
    --config min.insync.replicas="$ORDERS_AVRO_MIN_ISR" \
    --config cleanup.policy=delete

echo "✓ Topic '$ORDERS_AVRO_TOPIC' created"

# 3. Order-consumed topic
echo ""
echo "Creating '$CONSUMED_TOPIC' topic..."
kafka-topics --bootstrap-server "$BOOTSTRAP_SERVER" \
    --create \
    --if-not-exists \
    --topic "$CONSUMED_TOPIC" \
    --partitions "$CONSUMED_PARTITIONS" \
    --replication-factor "$CONSUMED_REPLICATION" \
    --config retention.ms="$CONSUMED_RETENTION" \
    --config min.insync.replicas="$CONSUMED_MIN_ISR" \
    --config cleanup.policy=delete

echo "✓ Topic '$CONSUMED_TOPIC' created"

# 4. Dead Letter Topic (DLT)
echo ""
echo "Creating '$DLT_TOPIC' topic..."
kafka-topics --bootstrap-server "$BOOTSTRAP_SERVER" \
    --create \
    --if-not-exists \
    --topic "$DLT_TOPIC" \
    --partitions "$DLT_PARTITIONS" \
    --replication-factor "$DLT_REPLICATION" \
    --config retention.ms="$DLT_RETENTION" \
    --config min.insync.replicas="$DLT_MIN_ISR" \
    --config cleanup.policy=delete

echo "✓ Topic '$DLT_TOPIC' created"

# Verify topics
echo ""
echo "=========================================="
echo "Topic Creation Complete. Verifying..."
echo "=========================================="
echo ""

kafka-topics --bootstrap-server "$BOOTSTRAP_SERVER" --list

echo ""
echo "Describing topics:"
echo ""

for topic in "$ORDERS_TOPIC" "$ORDERS_AVRO_TOPIC" "$CONSUMED_TOPIC" "$DLT_TOPIC"; do
    echo "--- Topic: $topic ---"
    kafka-topics --bootstrap-server "$BOOTSTRAP_SERVER" \
        --describe \
        --topic "$topic"
    echo ""
done

echo "=========================================="
echo "Kafka Topic Initialization Complete!"
echo "=========================================="
