#!/bin/bash

echo "=========================================="
echo "Cleaning up Kafka Platform"
echo "=========================================="

# Ask for confirmation
read -p "This will delete all kafka namespaces (kafka-platform, kafka-producers, kafka-consumers). Are you sure? (y/N) " -n 1 -r
echo
if [[ ! $REPLY =~ ^[Yy]$ ]]; then
    echo "Cleanup cancelled."
    exit 0
fi

echo ""
echo "Deleting namespaces and all resources..."

# Clean up any old Kafka UI services in default namespace
echo "Checking for Kafka UI services in default namespace..."
kubectl delete svc kafka-ui-service -n default 2>/dev/null || true

# Delete all three namespaces
kubectl delete namespace kafka-platform kafka-producers kafka-consumers

if [ $? -eq 0 ]; then
    echo ""
    echo "✓ Namespaces deleted successfully"
else
    echo ""
    echo "✗ Failed to delete namespaces"
    exit 1
fi

# Wait for namespaces to be fully deleted
echo ""
echo "Waiting for namespaces to be fully deleted..."
kubectl wait --for=delete namespace/kafka-platform --timeout=120s 2>/dev/null || true
kubectl wait --for=delete namespace/kafka-producers --timeout=120s 2>/dev/null || true
kubectl wait --for=delete namespace/kafka-consumers --timeout=120s 2>/dev/null || true

echo ""
echo "=========================================="
echo "Cleanup Complete!"
echo "=========================================="
echo ""
echo "Note: PersistentVolumes may need manual cleanup if not auto-deleted."
echo ""
echo "To verify:"
echo "  kubectl get pv"
echo "  kubectl get namespaces"
echo ""
