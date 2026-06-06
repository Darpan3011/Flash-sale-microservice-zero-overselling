package com.flashsale.order.consumer;

import com.flashsale.events.OrderRequestEvent;
import com.flashsale.order.service.OrderService;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
public class OrderEventConsumer {

    private final OrderService orderService;

    public OrderEventConsumer(OrderService orderService) {
        this.orderService = orderService;
    }

    @KafkaListener(
        topics = "sale.order.requested",
        groupId = "order-service",
        concurrency = "3"
    )
    public void onOrderRequested(OrderRequestEvent event) {
        orderService.processOrderRequest(event);
    }
}
