package com.kafka.platform.consumer.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory store for tracking processed order IDs.
 * In production, this would be backed by a database or distributed cache.
 * This ensures idempotent processing - same order can be replayed without side effects.
 */
@Slf4j
@Service
public class ProcessedOrderStore {

    private final Set<String> processedOrderIds = ConcurrentHashMap.newKeySet();

    public boolean hasBeenProcessed(String orderId) {
        return processedOrderIds.contains(orderId);
    }

    public void markAsProcessed(String orderId) {
        processedOrderIds.add(orderId);
        log.debug("Marked order {} as processed. Total processed: {}", orderId, processedOrderIds.size());
    }

    public int getProcessedCount() {
        return processedOrderIds.size();
    }
}
