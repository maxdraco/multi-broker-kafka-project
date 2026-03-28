# Validation Testing Guide

## Overview

The system implements **two layers of validation**:

1. **Schema Validation** (Avro + Apicurio Registry) - At producer, before Kafka
2. **Business Validation** (Consumer + DLT) - At consumer, after Kafka

This guide covers how to test both validation layers and understand their differences.

## Quick Start: Combined Validation Test

The easiest way to test both validation types is with the combined test job:

```bash
# Run the comprehensive validation test
kubectl apply -f k8s/validation-test-job.yaml

# Watch the test execution
kubectl logs -n kafka-producers job/validation-test -f
```

This job will:
1. **Test Schema Validation**: Send 10 Avro messages with schema violations (missing required fields)
2. **Test Business Validation**: Send 10 JSON messages with business rule violations (poison pills)
3. **Show the difference**: Messages blocked at producer vs messages routed to DLT

Expected results:
- **Schema validation failures**: 10 messages rejected at producer, 0 in Kafka, 0 in DLT
- **Business validation failures**: 10 messages in Kafka, consumer retries 3x each, all 10 in DLT

## Two Types of Validation

### Schema Validation (Avro + Registry)

**Where**: Producer (serialization)
**When**: Before sending to Kafka
**Fails**: `SerializationException`
**Result**: Message is NOT sent to Kafka
**DLT**: No (message doesn't exist)

**Example violations**:
- Missing required fields (`user`, `item`, `orderId`)
- Wrong data types (string instead of int)
- Fields not defined in schema

**Endpoint**: `POST /api/orders/avro/invalid`

### Business Validation (Consumer + DLT)

**Where**: Consumer (processing)
**When**: After reading from Kafka
**Fails**: `IllegalArgumentException`
**Result**: Message written to Kafka, consumer retries 3x, then DLT
**DLT**: Yes (after max retries)

**Example violations**:
- Poison pill items (`item = "POISON_PILL"`)
- Negative quantities (`quantity < 0`)
- Business rule violations (null user)

**Endpoint**: `POST /api/orders/invalid`

## How DLT Routing Works

1. **Consumer processes order** from `orders` topic
2. **Validation fails** (poison pill item, negative quantity, null user)
3. **Consumer retries** up to 3 times (configured in `max-retry-attempts`)
4. **After max retries**, order is routed to `orders.DLT` topic
5. **Offset is committed** so processing continues

## Testing Both Validation Types

### Option 1: Combined Validation Test (Recommended)

Run both schema validation and business validation tests together:

```bash
# Run comprehensive validation test
kubectl apply -f k8s/validation-test-job.yaml

# Watch execution
kubectl logs -n kafka-producers job/validation-test -f

# Verify schema validation failures (producer logs)
kubectl logs -n kafka-producers -l app=order-producer --tail=100 | grep -i "error\|exception\|failed"

# Verify business validation failures (consumer logs)
kubectl logs -n kafka-consumers -l app=order-consumer --tail=100 | grep -E "WARN|DLT|retry"

# Check DLT topic (should have 10 business validation failures)
kubectl exec -n kafka-platform kafka-0 -- \
  kafka-console-consumer --bootstrap-server localhost:9092 \
  --topic orders.DLT --from-beginning --max-messages 10
```

### Option 2: Test Only Business Validation (DLT)

Test only the business validation and DLT routing:

```bash
# Run the DLT test job
kubectl apply -f k8s/dlt-test-job.yaml

# Watch the job execution
kubectl logs -n kafka-producers job/dlt-test-generator -f

# Check consumer logs to see validation failures and DLT routing
kubectl logs -n kafka-consumers -l app=order-consumer -f
```

The job will:
- Send 10 invalid orders to the producer REST API
- Orders will have:
  - `POISON_PILL` items (invalid item name)
  - Negative quantities (-999)
  - Null users
- Consumer will fail validation 3 times per order
- All 10 orders will be routed to `orders.DLT` topic

### Option 2: Manual REST API Call

You can also trigger invalid orders manually via the producer API:

```bash
# From inside the cluster
kubectl run test-producer --rm -i --tty --image=curlimages/curl:latest \
  --restart=Never -n kafka-producers -- \
  curl -X POST http://order-producer-service:8080/api/orders/invalid \
  -H 'Content-Type: application/json' \
  -d '{"count": 5}'

# Response shows which invalid orders were sent
```

### Option 3: Test Only Schema Validation (Avro)

Test only Avro schema validation failures:

```bash
# From inside the cluster
kubectl run test-producer --rm -i --tty --image=curlimages/curl:latest \
  --restart=Never -n kafka-producers -- \
  curl -X POST http://order-producer-service:8080/api/orders/avro/invalid

# Check producer logs for SerializationException
kubectl logs -n kafka-producers -l app=order-producer --tail=50 | grep -i "serialization\|schema\|error"

# Verify orders-avro topic has NO invalid messages
kubectl exec -n kafka-platform kafka-0 -- \
  kafka-run-class kafka.tools.GetOffsetShell \
  --broker-list localhost:9092 \
  --topic orders-avro
```

**Expected result**: All 10 messages fail at producer with `SerializationException`. Zero messages written to `orders-avro` topic.

### Option 4: Direct Producer Call (from local machine)

If you have port-forwarding set up:

```bash
# Port forward the producer service
kubectl port-forward -n kafka-producers svc/order-producer-service 8080:8080

# Send invalid orders
curl -X POST http://localhost:8080/api/orders/invalid \
  -H 'Content-Type: application/json' \
  -d '{"count": 10}'
```

## Validation Comparison

| Aspect | Schema Validation (Avro) | Business Validation (DLT) |
|--------|--------------------------|---------------------------|
| **Location** | Producer (serialization) | Consumer (processing) |
| **Timing** | Before Kafka | After Kafka |
| **Message in Kafka?** | ❌ No - blocked | ✅ Yes - written successfully |
| **Consumer sees it?** | ❌ No | ✅ Yes (then fails) |
| **DLT routing?** | ❌ No - doesn't exist | ✅ Yes - after retries |
| **Error type** | `SerializationException` | `IllegalArgumentException` |
| **Retries** | ❌ None (immediate fail) | ✅ 3 attempts (configurable) |
| **Use case** | Structural correctness | Logical correctness |
| **Examples** | Missing required fields, wrong types | Poison pills, business rules |
| **Test endpoint** | `/api/orders/avro/invalid` | `/api/orders/invalid` |

## Validation Rules

### Schema Validation Rules (Avro)

Enforced by Apicurio Registry at producer serialization:

**Required fields** (must be present):
```json
{
  "orderId": "string",
  "user": "string",
  "item": "string",
  "quantity": "int",
  "timestamp": "long"
}
```

Missing any required field → `SerializationException` → message NOT sent.

### Business Validation Rules (Consumer)

The consumer rejects orders that match these criteria:

### 1. Poison Pill Items
```json
{
  "item": "POISON_PILL",
  "user": "test-user",
  "quantity": 1
}
```
**Error**: `IllegalArgumentException: Poison pill order detected`

### 2. Negative Quantities
```json
{
  "item": "laptop",
  "user": "test-user",
  "quantity": -999
}
```
**Error**: `IllegalArgumentException: Invalid order - quantity cannot be negative`

### 3. Null/Empty Users
```json
{
  "item": "phone",
  "user": null,
  "quantity": 1
}
```
**Error**: `IllegalArgumentException: Invalid order - user cannot be null or empty`

## Verifying Validation Results

### Schema Validation Results

**Check producer logs for serialization errors**:

```bash
kubectl logs -n kafka-producers -l app=order-producer --tail=100 | grep -i "error\|serialization\|failed"
```

Expected output:
```
ERROR Failed to produce Avro order: abc-123
org.apache.kafka.common.errors.SerializationException: Error serializing Avro message
Caused by: org.apache.avro.AvroRuntimeException: Field user is required
```

**Verify orders-avro topic has zero invalid messages**:

```bash
# Check message count
kubectl exec -n kafka-platform kafka-0 -- \
  kafka-run-class kafka.tools.GetOffsetShell \
  --broker-list localhost:9092 \
  --topic orders-avro

# Try to consume (should timeout with no messages if all failed validation)
kubectl exec -n kafka-platform kafka-0 -- \
  kafka-console-consumer --bootstrap-server localhost:9092 \
  --topic orders-avro --from-beginning --timeout-ms 5000
```

### Business Validation Results (DLT)

**Check consumer logs for retries and DLT routing**:

Look for retry attempts and DLT routing:

```bash
kubectl logs -n kafka-consumers -l app=order-consumer -f | grep -E "(WARN|DLT|retry)"
```

Expected output:
```
WARN  ... Error processing order abc-123: IllegalArgumentException: Poison pill order detected
WARN  ... Retry 1/3 for order abc-123
WARN  ... Retry 2/3 for order abc-123
WARN  ... Retry 3/3 for order abc-123
WARN  ... Max retry attempts reached for order abc-123. Sending to DLT.
INFO  ... Sending order abc-123 to DLT topic orders.DLT
INFO  ... Order abc-123 sent to DLT successfully
```

### 2. Check DLT Topic Contents

View messages in the DLT topic:

```bash
# List all DLT messages
kubectl exec -n kafka-platform kafka-0 -- \
  kafka-console-consumer \
  --bootstrap-server localhost:9092 \
  --topic orders.DLT \
  --from-beginning \
  --max-messages 10

# Count DLT messages
kubectl exec -n kafka-platform kafka-0 -- \
  kafka-run-class kafka.tools.GetOffsetShell \
  --broker-list localhost:9092 \
  --topic orders.DLT
```

### 3. Check Kafka UI

Open Kafka UI at http://localhost:30080 and navigate to:
- **Topics** → `orders.DLT` → View messages
- **Consumer Groups** → `order-tracker` → Check lag and offsets

## Configuration

DLT behavior is configured in `k8s/02-configmaps.yaml`:

```yaml
# Consumer Configuration
max-retry-attempts: "3"  # Number of retries before DLT
kafka-dlt-topic: "orders.DLT"  # DLT topic name

# Topic Configuration (DLT)
dlt-topic-name: "orders.DLT"
dlt-partitions: "3"
dlt-replication-factor: "3"
dlt-retention-ms: "2592000000"  # 30 days
```

## DLT Message Format

Messages in the DLT topic contain:
- **Original message** (failed order)
- **Same partition key** (user ID)
- **Headers** with retry count and error info

## Cleanup

Remove test jobs after completion:

```bash
# Remove combined validation test
kubectl delete job validation-test -n kafka-producers

# Remove DLT-only test (if run separately)
kubectl delete job dlt-test-generator -n kafka-producers
```

Both jobs automatically clean up after 300 seconds (5 minutes) due to `ttlSecondsAfterFinished: 300`.

## Quick Reference Commands

### Run Tests

```bash
# Combined test (schema + business validation)
kubectl apply -f k8s/validation-test-job.yaml
kubectl logs -n kafka-producers job/validation-test -f

# Business validation only (DLT)
kubectl apply -f k8s/dlt-test-job.yaml
kubectl logs -n kafka-producers job/dlt-test-generator -f
```

### Check Results

```bash
# Producer logs (schema validation errors)
kubectl logs -n kafka-producers -l app=order-producer --tail=100 | grep -i error

# Consumer logs (DLT routing)
kubectl logs -n kafka-consumers -l app=order-consumer --tail=100 | grep DLT

# DLT topic messages
kubectl exec -n kafka-platform kafka-0 -- \
  kafka-console-consumer --bootstrap-server localhost:9092 \
  --topic orders.DLT --from-beginning --max-messages 10

# Orders topic (valid messages)
kubectl exec -n kafka-platform kafka-0 -- \
  kafka-console-consumer --bootstrap-server localhost:9092 \
  --topic orders --from-beginning --max-messages 10

# Kafka UI
open http://localhost:30080
```

### Cleanup

```bash
kubectl delete job validation-test dlt-test-generator -n kafka-producers
```

## Production Considerations

For production deployments:

1. **Monitor both validation layers** - track schema validation failures AND DLT volume
2. **Schema governance** - enforce backward compatibility in Apicurio Registry
3. **Investigate failures** - review DLT messages to fix upstream issues
4. **Replay capability** - implement tooling to replay DLT messages after fixes
5. **Retention policy** - DLT retention set to 30 days (configurable)
6. **Exponential backoff** - consider implementing exponential backoff for retries
7. **Alerting** - alert on:
   - High schema validation failure rate (producer errors)
   - DLT message volume spikes
   - Consumer lag on DLT topic (unprocessed failures)
