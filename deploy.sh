#!/bin/bash
set -e

echo "=========================================="
echo "Deploying Multi-Broker Kafka Platform"
echo "Multi-Namespace Architecture"
echo "=========================================="

# Deploy namespaces
echo ""
echo "Step 1: Creating namespaces..."
kubectl apply -f k8s/01-namespaces.yaml

if [ $? -eq 0 ]; then
    echo "✓ Namespaces created"
    kubectl get namespaces | grep -E "kafka-platform|kafka-producers|kafka-consumers"
else
    echo "✗ Failed to create namespaces"
    exit 1
fi

# Deploy ConfigMaps
echo ""
echo "Step 2: Creating ConfigMaps..."
kubectl apply -f k8s/02-configmaps.yaml

if [ $? -eq 0 ]; then
    echo "✓ ConfigMaps created"
    echo "  - kafka-config (kafka-platform)"
    echo "  - producer-config (kafka-producers)"
    echo "  - consumer-config (kafka-consumers)"
    echo "  - topic-init-config (kafka-platform)"
    echo "  - kafka-ui-config (kafka-platform)"
else
    echo "✗ Failed to create ConfigMaps"
    exit 1
fi

# Deploy Kafka cluster
echo ""
echo "Step 3: Deploying Kafka cluster in kafka-platform namespace..."
kubectl apply -f k8s/kafka-statefulset.yaml

if [ $? -eq 0 ]; then
    echo "✓ Kafka StatefulSet deployed"
else
    echo "✗ Failed to deploy Kafka StatefulSet"
    exit 1
fi

# Wait for Kafka brokers to be ready
echo ""
echo "Step 4: Waiting for Kafka brokers to be ready..."
echo "This may take 1-2 minutes..."

kubectl wait --for=condition=ready pod \
    -l app=kafka \
    -n kafka-platform \
    --timeout=300s

if [ $? -eq 0 ]; then
    echo "✓ All Kafka brokers are ready"
else
    echo "✗ Kafka brokers failed to become ready"
    exit 1
fi

# Show Kafka pods
echo ""
echo "Kafka pods:"
kubectl get pods -n kafka-platform -l app=kafka

# Deploy Schema Registry
echo ""
echo "Step 5: Deploying Schema Registry in kafka-platform namespace..."
kubectl apply -f k8s/schema-registry-deployment.yaml

if [ $? -eq 0 ]; then
    echo "✓ Schema Registry deployed"
else
    echo "✗ Failed to deploy Schema Registry"
    exit 1
fi

# Wait for Schema Registry to be ready
echo "Waiting for Schema Registry to be ready (max 5 minutes)..."
kubectl wait --for=condition=ready pod \
    -l app=schema-registry \
    -n kafka-platform \
    --timeout=300s 2>/dev/null

if [ $? -eq 0 ]; then
    echo "✓ Schema Registry is ready"
    SCHEMA_REGISTRY_URL=$(kubectl get svc schema-registry -n kafka-platform -o jsonpath='{.spec.clusterIP}')
    echo "✓ Schema Registry available at: http://$SCHEMA_REGISTRY_URL:8081"
else
    echo "⚠ Schema Registry may still be starting..."
    kubectl get pods -n kafka-platform -l app=schema-registry
    echo "Continuing deployment..."
fi

# Deploy topic initialization job
echo ""
echo "Step 6: Running topic initialization job..."
kubectl delete job kafka-topic-init -n kafka-platform 2>/dev/null || true
sleep 2
kubectl apply -f k8s/kafka-topic-init-job.yaml

# Wait for topic init job to complete
echo "Waiting for topic initialization to complete (max 10 minutes)..."
kubectl wait --for=condition=complete job/kafka-topic-init \
    -n kafka-platform \
    --timeout=600s 2>/dev/null

# Check if job succeeded even if wait timed out
JOB_STATUS=$(kubectl get job kafka-topic-init -n kafka-platform -o jsonpath='{.status.succeeded}' 2>/dev/null)

if [ "$JOB_STATUS" = "1" ]; then
    echo "✓ Topics initialized successfully"
    echo ""
    echo "Topic init job logs:"
    kubectl logs -n kafka-platform job/kafka-topic-init --tail=20
elif [ $? -eq 0 ]; then
    echo "✓ Topics initialized"
    echo ""
    echo "Topic init job logs:"
    kubectl logs -n kafka-platform job/kafka-topic-init --tail=20
else
    echo "⚠ Topic initialization may still be running..."
    echo "Checking job status..."
    kubectl get job kafka-topic-init -n kafka-platform
    echo ""
    echo "Recent logs:"
    kubectl logs -n kafka-platform job/kafka-topic-init --tail=30

    # Continue anyway - topics might have been created
    echo ""
    echo "Continuing deployment (topics may already exist)..."
fi

# Deploy order producer service in kafka-producers namespace
echo ""
echo "Step 7: Deploying order producer service in kafka-producers namespace..."
kubectl apply -f k8s/order-producer-deployment.yaml

if [ $? -eq 0 ]; then
    echo "✓ Order producer service deployed"
else
    echo "✗ Failed to deploy order producer service"
    exit 1
fi

# Wait for producer to be ready
echo "Waiting for producer service to be ready (max 5 minutes)..."
kubectl wait --for=condition=ready pod \
    -l app=order-producer \
    -n kafka-producers \
    --timeout=300s 2>/dev/null

if [ $? -eq 0 ]; then
    echo "✓ Order producer service is ready"
else
    echo "⚠ Producer service may still be starting..."
    kubectl get pods -n kafka-producers -l app=order-producer
    echo "Continuing deployment..."
fi

# Deploy order consumer in kafka-consumers namespace
echo ""
echo "Step 8: Deploying order consumer in kafka-consumers namespace (3 replicas)..."
kubectl apply -f k8s/order-consumer-deployment.yaml

if [ $? -eq 0 ]; then
    echo "✓ Order consumer deployed"
else
    echo "✗ Failed to deploy order consumer"
    exit 1
fi

# Wait for consumers to be ready
echo "Waiting for consumer pods to be ready (max 5 minutes)..."
kubectl wait --for=condition=ready pod \
    -l app=order-consumer \
    -n kafka-consumers \
    --timeout=300s 2>/dev/null

if [ $? -eq 0 ]; then
    echo "✓ Order consumers are ready"
else
    echo "⚠ Some consumer pods may still be starting..."
    kubectl get pods -n kafka-consumers -l app=order-consumer
    echo "Continuing deployment..."
fi

# Run data generator job
echo ""
echo "Step 9: Running data generator job..."
kubectl delete job data-generator -n kafka-producers 2>/dev/null || true
sleep 2
kubectl apply -f k8s/data-generator-job.yaml

if [ $? -eq 0 ]; then
    echo "✓ Data generator job submitted"
    echo "  This job calls the producer service REST API to generate test data"
else
    echo "✗ Failed to submit data generator job"
    exit 1
fi

# Wait for data generator to complete
echo "Waiting for data generator to complete (max 10 minutes)..."
kubectl wait --for=condition=complete job/data-generator \
    -n kafka-producers \
    --timeout=600s 2>/dev/null

# Check if job succeeded
DATA_GEN_STATUS=$(kubectl get job data-generator -n kafka-producers -o jsonpath='{.status.succeeded}' 2>/dev/null)

if [ "$DATA_GEN_STATUS" = "1" ]; then
    echo "✓ Data generation completed successfully"
    echo ""
    echo "Data generator job logs:"
    kubectl logs -n kafka-producers job/data-generator --tail=30
elif [ $? -eq 0 ]; then
    echo "✓ Data generator job completed"
    echo ""
    echo "Data generator job logs:"
    kubectl logs -n kafka-producers job/data-generator --tail=30
else
    echo "⚠ Data generator may still be running..."
    echo "Checking job status..."
    kubectl get job data-generator -n kafka-producers
    echo ""
    echo "Recent logs:"
    kubectl logs -n kafka-producers job/data-generator --tail=50
    echo ""
    echo "Continuing deployment..."
fi

# Deploy Kafka UI - Last step for operational visibility
echo ""
echo "Step 10: Deploying Kafka UI in kafka-platform namespace..."
echo "  (Deployed last so you can observe the fully active system)"

# Clean up any old Kafka UI services in default namespace to avoid port conflicts
echo "Checking for old Kafka UI services on port 30080..."
OLD_SERVICE=$(kubectl get svc --all-namespaces -o json | grep -c '"nodePort": 30080' || echo "0")
if [ "$OLD_SERVICE" != "0" ]; then
    echo "⚠ Found existing service using port 30080, cleaning up..."
    kubectl delete svc kafka-ui-service -n default 2>/dev/null || true
    sleep 2
fi

kubectl apply -f k8s/kafka-ui-deployment.yaml

if [ $? -eq 0 ]; then
    echo "✓ Kafka UI deployed"
else
    echo "✗ Failed to deploy Kafka UI"
    exit 1
fi

# Wait for Kafka UI to be ready
echo "Waiting for Kafka UI to be ready (max 5 minutes)..."
kubectl wait --for=condition=ready pod \
    -l app=kafka-ui \
    -n kafka-platform \
    --timeout=300s 2>/dev/null

if [ $? -eq 0 ]; then
    echo "✓ Kafka UI is ready"
    # Verify service is created with NodePort
    KAFKA_UI_PORT=$(kubectl get svc kafka-ui -n kafka-platform -o jsonpath='{.spec.ports[0].nodePort}' 2>/dev/null)
    if [ "$KAFKA_UI_PORT" = "30080" ]; then
        echo "✓ Kafka UI service exposed on NodePort 30080"
    else
        echo "⚠ Warning: Kafka UI service port may not be configured correctly"
    fi
else
    echo "⚠ Kafka UI may still be starting..."
    kubectl get pods -n kafka-platform -l app=kafka-ui
    echo "Continuing to deployment summary..."
fi

# Show deployment status across all namespaces
echo ""
echo "=========================================="
echo "Deployment Status"
echo "=========================================="

echo ""
echo "Kafka Platform Namespace:"
kubectl get all -n kafka-platform

echo ""
echo "Kafka Producers Namespace:"
kubectl get all -n kafka-producers

echo ""
echo "Kafka Consumers Namespace:"
kubectl get all -n kafka-consumers

echo ""
echo "=========================================="
echo "Deployment Complete!"
echo "=========================================="
echo ""
echo "Architecture:"
echo "  - Kafka Cluster (3 brokers): kafka-platform namespace"
echo "  - Producer Service (REST API): kafka-producers namespace"
echo "  - Consumer Service (3 replicas): kafka-consumers namespace"
echo ""
echo "Kafka UI Access:"
echo "  http://localhost:30080 (NodePort - directly accessible)"
echo ""
echo "Validation Testing (Manual):"
echo "  # Run comprehensive validation test (Schema + Business)"
echo "  kubectl apply -f k8s/validation-test-job.yaml"
echo "  kubectl logs -n kafka-producers job/validation-test -f"
echo ""
echo "  # Run DLT-only test"
echo "  kubectl apply -f k8s/dlt-test-job.yaml"
echo "  kubectl logs -n kafka-producers job/dlt-test -f"
echo ""
echo "Useful commands:"
echo ""
echo "  # View pods in each namespace"
echo "  kubectl get pods -n kafka-platform"
echo "  kubectl get pods -n kafka-producers"
echo "  kubectl get pods -n kafka-consumers"
echo ""
echo "  # View producer service logs"
echo "  kubectl logs -n kafka-producers -l app=order-producer -f"
echo ""
echo "  # View data generator job logs"
echo "  kubectl logs -n kafka-producers job/data-generator"
echo ""
echo "  # Trigger manual data generation"
echo "  kubectl run --rm -i --tty test-producer --image=curlimages/curl:latest --restart=Never -n kafka-producers -- curl -X POST http://order-producer-service:8080/api/orders/batch -H 'Content-Type: application/json' -d '{\"count\": 10, \"delayMs\": 500}'"
echo ""
echo "  # View consumer logs"
echo "  kubectl logs -n kafka-consumers -l app=order-consumer -f"
echo ""
echo "  # View Kafka broker logs"
echo "  kubectl logs -n kafka-platform kafka-0"
echo ""
echo "  # List topics"
echo "  kubectl exec -n kafka-platform kafka-0 -- kafka-topics --bootstrap-server localhost:9092 --list"
echo ""
echo "  # View consumer group status"
echo "  kubectl exec -n kafka-platform kafka-0 -- kafka-consumer-groups --bootstrap-server localhost:9092 --describe --group order-tracker"
echo ""
