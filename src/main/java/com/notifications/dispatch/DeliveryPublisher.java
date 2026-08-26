package com.notifications.dispatch;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Stands in for a message broker (no Kafka/RabbitMQ/Docker in this project): an in-memory
 * queue plus a small worker pool that pulls delivery IDs and hands them to
 * {@link DeliveryDispatchService}. Deliveries are always persisted before being published here
 * (see NotificationService), so if the process dies between persist and publish, or a publish
 * is simply dropped, the delivery isn't lost - {@link DeliveryReconciliationScheduler} will
 * find and republish it on its next sweep. That combination is what gives the system its
 * at-least-once guarantee without an external broker.
 */
@Component
public class DeliveryPublisher {

    private static final Logger log = LoggerFactory.getLogger(DeliveryPublisher.class);

    private final BlockingQueue<String> queue = new LinkedBlockingQueue<>();
    private final DeliveryDispatchService dispatchService;
    private final int workerCount;
    private ExecutorService executor;
    private volatile boolean running = true;

    public DeliveryPublisher(
            DeliveryDispatchService dispatchService,
            @Value("${notification.dispatch.worker-count:4}") int workerCount) {
        this.dispatchService = dispatchService;
        this.workerCount = workerCount;
    }

    public void publish(String deliveryId) {
        queue.add(deliveryId);
    }

    @PostConstruct
    void start() {
        executor = Executors.newFixedThreadPool(workerCount, r -> {
            Thread t = new Thread(r, "delivery-worker");
            t.setDaemon(true);
            return t;
        });
        for (int i = 0; i < workerCount; i++) {
            executor.submit(this::workerLoop);
        }
    }

    private void workerLoop() {
        while (running) {
            try {
                String deliveryId = queue.poll(1, TimeUnit.SECONDS);
                if (deliveryId != null) {
                    dispatchService.process(deliveryId);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (Exception e) {
                log.error("Unexpected error processing delivery from queue", e);
            }
        }
    }

    @PreDestroy
    void stop() {
        running = false;
        if (executor != null) {
            executor.shutdownNow();
        }
    }
}
