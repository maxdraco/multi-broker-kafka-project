package com.kafka.platform.consumer.service;

import com.kafka.platform.consumer.model.Order;
import com.kafka.platform.consumer.model.OrderConfirmation;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Headers;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;

@Slf4j
@Service
public class OrderConsumerService {

    private final ProcessedOrderStore processedOrderStore;
    private final KafkaTemplate<String, OrderConfirmation> kafkaTemplate;
    private final KafkaTemplate<String, Order> dltKafkaTemplate;

    @Value("${app.kafka.consumed-topic}")
    private String consumedTopic;

    @Value("${app.kafka.dlt-topic}")
    private String dltTopic;

    @Value("${app.kafka.topic}")
    private String ordersTopic;

    @Value("${app.consumer.max-retry-attempts}")
    private int maxRetryAttempts;

    // Retry header key
    private static final String RETRY_COUNT_HEADER = "retry-count";

    public OrderConsumerService(
            ProcessedOrderStore processedOrderStore,
            KafkaTemplate<String, OrderConfirmation> kafkaTemplate,
            KafkaTemplate<String, Order> dltKafkaTemplate) {
        this.processedOrderStore = processedOrderStore;
        this.kafkaTemplate = kafkaTemplate;
        this.dltKafkaTemplate = dltKafkaTemplate;
    }

    @KafkaListener(
            topics = "${app.kafka.topic}",
            groupId = "${spring.kafka.consumer.group-id}",
            containerFactory = "kafkaListenerContainerFactory"
    )
    public void consumeOrder(
            ConsumerRecord<String, Order> record,
            Acknowledgment acknowledgment,
            @org.springframework.messaging.handler.annotation.Header(KafkaHeaders.RECEIVED_PARTITION) int partition) {

        Order order = record.value();
        String orderId = order.getOrderId();

        log.info("Received order: partition={} | offset={} | {} x {} from {}",
                partition, record.offset(), order.getQuantity(), order.getItem(), order.getUser());

        try {
            // Check for idempotency - skip if already processed
            if (processedOrderStore.hasBeenProcessed(orderId)) {
                log.info("Order {} already processed, skipping (idempotent)", orderId);
                acknowledgment.acknowledge();
                return;
            }

            // Process the order (simulate business logic)
            processOrder(order);

            // Mark as processed for idempotency
            processedOrderStore.markAsProcessed(orderId);

            // Produce confirmation to outbox topic
            OrderConfirmation confirmation = OrderConfirmation.builder()
                    .orderId(order.getOrderId())
                    .user(order.getUser())
                    .item(order.getItem())
                    .quantity(order.getQuantity())
                    .status("consumed")
                    .sourcePartition(partition)
                    .sourceOffset(record.offset())
                    .build();

            kafkaTemplate.send(consumedTopic, order.getUser(), confirmation)
                    .whenComplete((result, ex) -> {
                        if (ex == null) {
                            log.info("  ↳ Confirmation produced to {} | partition {} | offset {}",
                                    result.getRecordMetadata().topic(),
                                    result.getRecordMetadata().partition(),
                                    result.getRecordMetadata().offset());
                        } else {
                            log.error("Failed to produce confirmation for order {}: {}",
                                    orderId, ex.getMessage());
                        }
                    });

            // Commit offset manually after successful processing
            acknowledgment.acknowledge();
            log.debug("Offset committed for order {}", orderId);

        } catch (Exception e) {
            log.error("Error processing order {}: {}", orderId, e.getMessage(), e);

            // Get retry count from Kafka headers
            int retryCount = getRetryCountFromHeaders(record.headers());

            if (retryCount >= maxRetryAttempts) {
                log.warn("Max retry attempts ({}) reached for order {}. Sending to DLT.",
                        maxRetryAttempts, orderId);
                sendToDLT(record, e);
                // Acknowledge to move forward
                acknowledgment.acknowledge();
            } else {
                // Increment retry count and republish to same topic with updated header
                int newRetryCount = retryCount + 1;
                log.warn("Retry attempt {}/{} for order {} (partition={}, offset={})",
                        newRetryCount, maxRetryAttempts, orderId, partition, record.offset());

                // Republish message to same topic with incremented retry count in headers
                republishWithRetryCount(record, newRetryCount);

                // Acknowledge original message (we've republished it with updated retry count)
                acknowledgment.acknowledge();
            }
        }
    }

    private void processOrder(Order order) {
        // Simulate business logic
        log.debug("Processing order: {} - {} x {} for {}",
                order.getOrderId(), order.getQuantity(), order.getItem(), order.getUser());

        // Validation: reject poison pill orders for DLT testing
        if ("POISON_PILL".equals(order.getItem())) {
            throw new IllegalArgumentException("Poison pill order detected - item cannot be POISON_PILL");
        }

        if (order.getQuantity() != null && order.getQuantity() < 0) {
            throw new IllegalArgumentException("Invalid order - quantity cannot be negative: " + order.getQuantity());
        }

        if (order.getUser() == null || order.getUser().trim().isEmpty()) {
            throw new IllegalArgumentException("Invalid order - user cannot be null or empty");
        }

        // Simulate processing time
        try {
            Thread.sleep(100);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Get retry count from Kafka message headers
     */
    private int getRetryCountFromHeaders(Headers headers) {
        org.apache.kafka.common.header.Header retryHeader = headers.lastHeader(RETRY_COUNT_HEADER);
        if (retryHeader == null) {
            return 0;
        }
        try {
            return Integer.parseInt(new String(retryHeader.value(), StandardCharsets.UTF_8));
        } catch (NumberFormatException e) {
            log.warn("Invalid retry count header value, defaulting to 0");
            return 0;
        }
    }

    /**
     * Republish message to same topic with incremented retry count in headers
     */
    private void republishWithRetryCount(ConsumerRecord<String, Order> record, int newRetryCount) {
        Order order = record.value();

        log.info("Republishing order {} to {} with retry count {}",
                order.getOrderId(), ordersTopic, newRetryCount);

        // Create ProducerRecord with retry count header
        org.apache.kafka.clients.producer.ProducerRecord<String, Order> producerRecord =
                new org.apache.kafka.clients.producer.ProducerRecord<>(
                        ordersTopic,
                        record.key(),
                        order
                );

        // Add retry count header
        producerRecord.headers().add(
                new RecordHeader(RETRY_COUNT_HEADER,
                        String.valueOf(newRetryCount).getBytes(StandardCharsets.UTF_8))
        );

        // Send to orders topic
        dltKafkaTemplate.send(producerRecord)
                .whenComplete((result, ex) -> {
                    if (ex == null) {
                        log.debug("Order {} republished successfully with retry count {}",
                                order.getOrderId(), newRetryCount);
                    } else {
                        log.error("Failed to republish order {} with retry count {}: {}",
                                order.getOrderId(), newRetryCount, ex.getMessage());
                    }
                });
    }

    /**
     * Send message to Dead Letter Topic
     */
    private void sendToDLT(ConsumerRecord<String, Order> record, Exception exception) {
        log.info("Sending order {} to DLT topic {}", record.value().getOrderId(), dltTopic);

        dltKafkaTemplate.send(dltTopic, record.key(), record.value())
                .whenComplete((result, ex) -> {
                    if (ex == null) {
                        log.info("Order {} sent to DLT successfully", record.value().getOrderId());
                    } else {
                        log.error("Failed to send order {} to DLT: {}",
                                record.value().getOrderId(), ex.getMessage());
                    }
                });
    }
}
