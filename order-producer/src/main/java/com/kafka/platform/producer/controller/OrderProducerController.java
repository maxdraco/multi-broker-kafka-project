package com.kafka.platform.producer.controller;

import com.kafka.platform.producer.model.Order;
import com.kafka.platform.producer.service.AvroOrderProducerService;
import com.kafka.platform.producer.service.OrderProducerService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/orders")
public class OrderProducerController {

    private final OrderProducerService producerService;
    private final AvroOrderProducerService avroProducerService;

    public OrderProducerController(
            OrderProducerService producerService,
            AvroOrderProducerService avroProducerService) {
        this.producerService = producerService;
        this.avroProducerService = avroProducerService;
    }

    @PostMapping
    public ResponseEntity<String> produceOrder(@RequestBody Order order) {
        log.info("Received request to produce order: {}", order.getOrderId());
        producerService.produceOrder(order);
        return ResponseEntity.ok("Order submitted: " + order.getOrderId());
    }

    @PostMapping("/random")
    public ResponseEntity<String> produceRandomOrder() {
        Order order = Order.createRandom();
        log.info("Producing random order: {}", order.getOrderId());
        producerService.produceOrder(order);
        return ResponseEntity.ok("Random order submitted: " + order.getOrderId());
    }

    @PostMapping("/batch")
    public ResponseEntity<BatchResponse> produceBatch(@RequestBody BatchRequest request) {
        log.info("Received request to produce {} orders with {}ms delay",
                request.getCount(), request.getDelayMs());

        List<String> orderIds = new ArrayList<>();

        for (int i = 0; i < request.getCount(); i++) {
            Order order = Order.createRandom();
            producerService.produceOrder(order);
            orderIds.add(order.getOrderId());

            if (i < request.getCount() - 1 && request.getDelayMs() > 0) {
                try {
                    Thread.sleep(request.getDelayMs());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    log.error("Interrupted while sleeping between orders", e);
                    break;
                }
            }
        }

        log.info("Batch production completed: {} orders", orderIds.size());

        return ResponseEntity.ok(new BatchResponse(orderIds.size(), orderIds));
    }

    @PostMapping("/avro/random")
    public ResponseEntity<String> produceRandomAvroOrder() {
        Order order = Order.createRandom();
        log.info("Producing random Avro order: {}", order.getOrderId());
        avroProducerService.produceAvroOrder(order);
        return ResponseEntity.ok("Avro order submitted: " + order.getOrderId());
    }

    @PostMapping("/avro/batch")
    public ResponseEntity<BatchResponse> produceAvroBatch(@RequestBody BatchRequest request) {
        log.info("Received request to produce {} Avro orders with {}ms delay",
                request.getCount(), request.getDelayMs());

        List<String> orderIds = new ArrayList<>();

        for (int i = 0; i < request.getCount(); i++) {
            Order order = Order.createRandom();
            avroProducerService.produceAvroOrder(order);
            orderIds.add(order.getOrderId());

            if (i < request.getCount() - 1 && request.getDelayMs() > 0) {
                try {
                    Thread.sleep(request.getDelayMs());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    log.error("Interrupted while sleeping between orders", e);
                    break;
                }
            }
        }

        log.info("Avro batch production completed: {} orders", orderIds.size());

        return ResponseEntity.ok(new BatchResponse(orderIds.size(), orderIds));
    }

    @PostMapping("/avro/invalid")
    public ResponseEntity<Map<String, Object>> produceInvalidOrders() {
        log.info("Producing 10 invalid orders for schema validation testing");

        List<Map<String, String>> results = new ArrayList<>();
        String[] invalidationTypes = {
                "missing-user", "missing-item", "negative-quantity",
                "missing-user", "missing-item", "negative-quantity",
                "missing-user", "missing-item", "negative-quantity",
                "all-missing"
        };

        for (int i = 0; i < 10; i++) {
            String invalidationType = invalidationTypes[i];
            String orderId = avroProducerService.produceInvalidOrder(invalidationType);

            Map<String, String> result = new HashMap<>();
            result.put("orderId", orderId);
            result.put("invalidationType", invalidationType);
            results.add(result);

            // Small delay between attempts
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }

        Map<String, Object> response = new HashMap<>();
        response.put("attempted", results.size());
        response.put("results", results);
        response.put("message", "Check producer logs for validation errors");

        return ResponseEntity.ok(response);
    }

    @PostMapping("/invalid")
    public ResponseEntity<Map<String, Object>> produceInvalidOrders(@RequestBody(required = false) InvalidOrderRequest request) {
        int count = (request != null && request.getCount() > 0) ? request.getCount() : 10;

        log.info("Producing {} invalid orders for DLT testing", count);

        List<Map<String, String>> results = new ArrayList<>();
        String[] invalidTypes = {
                "poison-pill",      // Item = "POISON_PILL"
                "negative-quantity",// Quantity = -999
                "null-user",        // User = null
                "poison-pill",
                "negative-quantity",
                "null-user",
                "poison-pill",
                "negative-quantity",
                "null-user",
                "poison-pill"
        };

        for (int i = 0; i < count; i++) {
            String invalidType = invalidTypes[i % invalidTypes.length];
            Order order = null;

            switch (invalidType) {
                case "poison-pill":
                    order = Order.builder()
                            .orderId(java.util.UUID.randomUUID().toString())
                            .user("test-user-" + i)
                            .item("POISON_PILL")
                            .quantity(1)
                            .build();
                    log.warn("Producing poison pill order: {}", order.getOrderId());
                    break;
                case "negative-quantity":
                    order = Order.builder()
                            .orderId(java.util.UUID.randomUUID().toString())
                            .user("test-user-" + i)
                            .item("laptop")
                            .quantity(-999)
                            .build();
                    log.warn("Producing negative quantity order: {}", order.getOrderId());
                    break;
                case "null-user":
                    order = Order.builder()
                            .orderId(java.util.UUID.randomUUID().toString())
                            .user(null)
                            .item("phone")
                            .quantity(1)
                            .build();
                    log.warn("Producing null user order: {}", order.getOrderId());
                    break;
            }

            producerService.produceOrder(order);

            Map<String, String> result = new HashMap<>();
            result.put("orderId", order.getOrderId());
            result.put("invalidationType", invalidType);
            results.add(result);

            // Small delay between invalid orders
            if (i < count - 1) {
                try {
                    Thread.sleep(200);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }

        Map<String, Object> response = new HashMap<>();
        response.put("attempted", results.size());
        response.put("results", results);
        response.put("message", "Check consumer logs and DLT topic for failed orders");

        return ResponseEntity.ok(response);
    }

    @GetMapping("/health")
    public ResponseEntity<String> health() {
        return ResponseEntity.ok("Producer service is healthy");
    }

    public static class InvalidOrderRequest {
        private int count = 10;

        public int getCount() {
            return count;
        }

        public void setCount(int count) {
            this.count = count;
        }
    }

    public static class BatchRequest {
        private int count = 10;
        private long delayMs = 500;

        public int getCount() {
            return count;
        }

        public void setCount(int count) {
            this.count = count;
        }

        public long getDelayMs() {
            return delayMs;
        }

        public void setDelayMs(long delayMs) {
            this.delayMs = delayMs;
        }
    }

    public static class BatchResponse {
        private int count;
        private List<String> orderIds;

        public BatchResponse(int count, List<String> orderIds) {
            this.count = count;
            this.orderIds = orderIds;
        }

        public int getCount() {
            return count;
        }

        public void setCount(int count) {
            this.count = count;
        }

        public List<String> getOrderIds() {
            return orderIds;
        }

        public void setOrderIds(List<String> orderIds) {
            this.orderIds = orderIds;
        }
    }
}
