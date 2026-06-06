package com.flashsale.sale.consumer;

import com.flashsale.events.OrderFailedEvent;
import com.flashsale.sale.service.StockService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
@Slf4j
public class SaleEventConsumer {

    private final StockService stockService;

    public SaleEventConsumer(StockService stockService) {
        this.stockService = stockService;
    }

    @KafkaListener(topics = "order.failed", groupId = "sale-service")
    public void onOrderFailed(OrderFailedEvent event) {
        stockService.releaseStock(event.saleId(), event.userId());
        log.warn("Order {} failed — stock restored for sale {}", event.orderId(), event.saleId());
    }
}
