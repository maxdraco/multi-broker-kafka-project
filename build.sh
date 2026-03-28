#!/bin/bash
set -e

echo "=========================================="
echo "Building Multi-Broker Kafka Platform"
echo "=========================================="

# Set JAVA_HOME to Java 21 for Maven build
export JAVA_HOME=/Library/Java/JavaVirtualMachines/amazon-corretto-21.jdk/Contents/Home
echo ""
echo "Using Java: $JAVA_HOME"
java -version

# Build Spring Boot applications
echo ""
echo "Step 1: Building Spring Boot applications with Maven..."
mvn clean package -DskipTests

if [ $? -eq 0 ]; then
    echo "✓ Maven build successful"
else
    echo "✗ Maven build failed"
    exit 1
fi

# Build Docker images (using nerdctl for Rancher Desktop)
echo ""
echo "Step 2: Building container images with nerdctl..."
echo "Building images into k8s.io namespace for Kubernetes access"
echo ""

# Build order-producer
echo "Building order-producer:1.0.0..."
nerdctl --namespace k8s.io build -t order-producer:1.0.0 -t order-producer:latest ./order-producer

if [ $? -eq 0 ]; then
    echo "✓ order-producer image built"
else
    echo "✗ Failed to build order-producer image"
    exit 1
fi

# Build order-consumer
echo ""
echo "Building order-consumer:1.0.0..."
nerdctl --namespace k8s.io build -t order-consumer:1.0.0 -t order-consumer:latest ./order-consumer

if [ $? -eq 0 ]; then
    echo "✓ order-consumer image built"
else
    echo "✗ Failed to build order-consumer image"
    exit 1
fi

# Build kafka-init-job
echo ""
echo "Building kafka-init-job:1.0.0..."
nerdctl --namespace k8s.io build -t kafka-init-job:1.0.0 -t kafka-init-job:latest ./kafka-init-job

if [ $? -eq 0 ]; then
    echo "✓ kafka-init-job image built"
else
    echo "✗ Failed to build kafka-init-job image"
    exit 1
fi

# List images
echo ""
echo "Step 3: Verifying container images..."
nerdctl --namespace k8s.io images | grep -E "order-producer|order-consumer|kafka-init-job" || true

echo ""
echo "=========================================="
echo "Build Complete!"
echo "=========================================="
echo ""
echo "Images built:"
echo "  - order-producer:1.0.0"
echo "  - order-consumer:1.0.0"
echo "  - kafka-init-job:1.0.0"
echo ""
echo "Next step: Run ./deploy.sh to deploy to Kubernetes"
