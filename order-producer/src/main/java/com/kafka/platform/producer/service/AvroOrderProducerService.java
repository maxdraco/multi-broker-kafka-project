package com.kafka.platform.producer.service;

import com.kafka.platform.avro.Order;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.stereotype.Service;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;

@Slf4j
@Service
public class AvroOrderProducerService {

    private final KafkaTemplate<String, Order> avroKafkaTemplate;
    private final String topic;

    public AvroOrderProducerService(
            KafkaTemplate<String, Order> avroKafkaTemplate,
            @Value("${app.kafka.topic}") String topic) {
        this.avroKafkaTemplate = avroKafkaTemplate;
        this.topic = topic;
    }

    public void produceAvroOrder(com.kafka.platform.producer.model.Order jsonOrder) {
        // Convert JSON Order to Avro Order
        Order avroOrder = Order.newBuilder()
                .setOrderId(jsonOrder.getOrderId())
                .setUser(jsonOrder.getUser())
                .setItem(jsonOrder.getItem())
                .setQuantity(jsonOrder.getQuantity())
                .setTimestamp(System.currentTimeMillis())
                .build();

        String key = avroOrder.getUser().toString();

        log.info("Producing Avro order: {} for user: {} - {} x {}",
                avroOrder.getOrderId(), avroOrder.getUser(),
                avroOrder.getQuantity(), avroOrder.getItem());

        CompletableFuture<SendResult<String, Order>> future =
                avroKafkaTemplate.send(topic, key, avroOrder);

        future.whenComplete((result, ex) -> {
            if (ex == null) {
                log.debug("Avro order produced successfully: {} to partition {}",
                        avroOrder.getOrderId(),
                        result.getRecordMetadata().partition());
            } else {
                log.error("Failed to produce Avro order: {}",
                        avroOrder.getOrderId(), ex);
            }
        });
    }

    /**
     * Produces an invalid order that will fail schema validation
     * This demonstrates schema registry validation in action
     */
    public String produceInvalidOrder(String invalidationType) {
        String orderId = UUID.randomUUID().toString();
        
        try {
            Order.Builder builder = Order.newBuilder()
                    .setOrderId(orderId)
                    .setTimestamp(System.currentTimeMillis());

            switch (invalidationType) {
                case "missing-user":
                    // Missing required field 'user' - will fail validation
                    builder.setItem("laptop").setQuantity(1);
                    log.warn("Attempting to produce order with missing 'user' field");
                    break;
                case "missing-item":
                    // Missing required field 'item' - will fail validation
                    builder.setUser("alice").setQuantity(1);
                    log.warn("Attempting to produce order with missing 'item' field");
                    break;
                case "negative-quantity":
                    // Negative quantity (logical validation, but schema allows int)
                    builder.setUser("bob").setItem("phone").setQuantity(-5);
                    log.warn("Attempting to produce order with negative quantity: -5");
                    break;
                default:
                    // All fields missing
                    log.warn("Attempting to produce order with all required fields missing");
            }

            Order invalidOrder = builder.build();
            String key = invalidOrder.getUser() != null ? invalidOrder.getUser().toString() : "unknown";
            
            CompletableFuture<SendResult<String, Order>> future =
                    avroKafkaTemplate.send(topic, key, invalidOrder);

            future.whenComplete((result, ex) -> {
                if (ex == null) {
                    log.info("Invalid order somehow succeeded: {}", orderId);
                } else {
                    log.error("Invalid order failed as expected: {} - Error: {}",
                            orderId, ex.getMessage());
                }
            });

            return orderId;
        } catch (Exception e) {
            log.error("Exception while creating invalid order: {}", e.getMessage());
            return orderId + " (failed: " + e.getMessage() + ")";
        }
    }
}
