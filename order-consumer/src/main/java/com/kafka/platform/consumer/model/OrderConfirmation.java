package com.kafka.platform.consumer.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OrderConfirmation {

    private String orderId;
    private String user;
    private String item;
    private Integer quantity;
    private String status;
    private Integer sourcePartition;
    private Long sourceOffset;
}
