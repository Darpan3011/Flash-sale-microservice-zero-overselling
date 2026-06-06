package com.flashsale.notification.service;

import com.darpan.communication.service.EmailService;
import com.flashsale.events.OrderConfirmedEvent;
import com.flashsale.events.WaitlistNotifyEvent;
import org.springframework.stereotype.Service;

@Service
public class NotificationService {

    private final EmailService emailService;

    public NotificationService(EmailService emailService) {
        this.emailService = emailService;
    }

    public void sendPurchaseConfirmation(OrderConfirmedEvent event) {
        emailService.sendEmail(
            "dummmy3012@gmail.com",
            "Purchase Confirmed — Order #" + event.orderId(),
            "Your purchase is confirmed! Order: " + event.orderId() + " | Amount: ₹" + event.amount(),
            "dummmy3012@gmail.com",
            "Flash Sale",
            null
        );
    }

    public void sendWaitlistAlert(WaitlistNotifyEvent event) {
        emailService.sendEmail(
            "dummmy3012@gmail.com",
            "Stock Available — Hurry!",
            "Good news! The item you were waiting for is back in stock. Visit now before it sells out again.",
            "dummmy3012@gmail.com",
            "Flash Sale",
            null
        );
    }
}
