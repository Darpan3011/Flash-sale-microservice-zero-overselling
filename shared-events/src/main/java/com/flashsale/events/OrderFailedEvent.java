package com.flashsale.events;

import java.util.UUID;

public record OrderFailedEvent(
    UUID orderId,
    UUID saleId,
    String userId,
    String reason
) {}
