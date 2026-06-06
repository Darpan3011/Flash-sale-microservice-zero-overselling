package com.flashsale.notification.consumer;

import com.flashsale.events.OrderConfirmedEvent;
import com.flashsale.events.WaitlistNotifyEvent;
import com.flashsale.notification.service.NotificationService;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
public class NotificationEventConsumer {

    private final NotificationService notificationService;

    public NotificationEventConsumer(NotificationService notificationService) {
        this.notificationService = notificationService;
    }

    @KafkaListener(topics = "order.confirmed", groupId = "notification-service")
    public void onOrderConfirmed(OrderConfirmedEvent event) {
        notificationService.sendPurchaseConfirmation(event);
    }

    @KafkaListener(topics = "waitlist.notify", groupId = "notification-service")
    public void onWaitlistNotify(WaitlistNotifyEvent event) {
        notificationService.sendWaitlistAlert(event);
    }
}
