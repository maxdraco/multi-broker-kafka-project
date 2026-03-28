package com.kafka.platform.producer.service;

import com.kafka.platform.producer.model.Order;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.stereotype.Service;

import java.util.concurrent.CompletableFuture;

@Slf4j
@Service
public class OrderProducerService {

    private final KafkaTemplate<String, Order> kafkaTemplate;
    private final String topic;

    public OrderProducerService(
            KafkaTemplate<String, Order> kafkaTemplate,
            @Value("${app.kafka.topic}") String topic) {
        this.kafkaTemplate = kafkaTemplate;
        this.topic = topic;
    }

    /**
     * Produces an order to Kafka with key-based partitioning.
     * The user ID is used as the key to ensure all orders for the same user
     * go to the same partition, maintaining ordering.
     */
    public void produceOrder(Order order) {
        String key = order.getUser();

        log.info("Producing order: {} for user: {} - {} x {}",
                order.getOrderId(), order.getUser(), order.getQuantity(), order.getItem());

        CompletableFuture<SendResult<String, Order>> future =
                kafkaTemplate.send(topic, key, order);

        future.whenComplete((result, ex) -> {
            if (ex == null) {
                log.info("Order {} delivered to topic {} | partition {} | offset {}",
                        order.getOrderId(),
                        result.getRecordMetadata().topic(),
                        result.getRecordMetadata().partition(),
                        result.getRecordMetadata().offset());
            } else {
                log.error("Failed to deliver order {}: {}",
                        order.getOrderId(), ex.getMessage(), ex);
            }
        });
    }
}
