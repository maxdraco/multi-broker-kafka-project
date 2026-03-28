package com.kafka.platform.producer.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Order {

    private String orderId;
    private String user;
    private String item;
    private Integer quantity;

    public static Order createRandom() {
        String[] users = {"alice", "bob", "charlie", "diana", "eve"};
        String[] items = {"laptop", "phone", "tablet", "monitor", "keyboard", "mouse"};

        return Order.builder()
                .orderId(UUID.randomUUID().toString())
                .user(users[(int) (Math.random() * users.length)])
                .item(items[(int) (Math.random() * items.length)])
                .quantity((int) (Math.random() * 10) + 1)
                .build();
    }
}
