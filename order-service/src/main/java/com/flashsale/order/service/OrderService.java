package com.flashsale.order.service;

import com.flashsale.events.OrderConfirmedEvent;
import com.flashsale.events.OrderFailedEvent;
import com.flashsale.events.OrderRequestEvent;
import com.flashsale.order.entity.Order;
import com.flashsale.order.entity.OrderStatus;
import com.flashsale.order.repository.OrderRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
@Slf4j
public class OrderService {

    private final OrderRepository orderRepo;
    private final KafkaTemplate<String, Object> kafkaTemplate;

    public OrderService(OrderRepository orderRepo, KafkaTemplate<String, Object> kafkaTemplate) {
        this.orderRepo = orderRepo;
        this.kafkaTemplate = kafkaTemplate;
    }

    public void processOrderRequest(OrderRequestEvent event) {
        if (orderRepo.existsByIdempotencyKey(event.idempotencyKey())) {
            log.warn("Duplicate order request ignored: {}", event.idempotencyKey());
            return;
        }

        try {
            Order order = new Order();
            order.setId(event.orderId());
            order.setSaleId(event.saleId());
            order.setUserId(event.userId());
            order.setProductId(event.productId());
            order.setAmount(event.amount());
            order.setStatus(OrderStatus.CONFIRMED);
            order.setIdempotencyKey(event.idempotencyKey());
            orderRepo.save(order);

            kafkaTemplate.send("order.confirmed",
                new OrderConfirmedEvent(event.orderId(), event.saleId(), event.userId(), event.amount()));

        } catch (Exception e) {
            log.error("Failed to persist order {}: {}", event.orderId(), e.getMessage());
            kafkaTemplate.send("order.failed",
                new OrderFailedEvent(event.orderId(), event.saleId(), event.userId(), e.getMessage()));
        }
    }
}
