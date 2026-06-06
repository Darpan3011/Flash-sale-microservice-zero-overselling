package com.flashsale.waitlist.consumer;

import com.flashsale.events.SaleRestockedEvent;
import com.flashsale.waitlist.service.WaitlistService;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
public class WaitlistEventConsumer {

    private final WaitlistService waitlistService;

    public WaitlistEventConsumer(WaitlistService waitlistService) {
        this.waitlistService = waitlistService;
    }

    @KafkaListener(topics = "sale.restocked", groupId = "waitlist-service")
    public void onSaleRestocked(SaleRestockedEvent event) {
        waitlistService.notifyTopWaiters(event.saleId(), event.quantity());
    }
}
