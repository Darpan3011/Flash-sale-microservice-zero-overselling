package com.flashsale.events;

import java.math.BigDecimal;
import java.util.UUID;

public record OrderConfirmedEvent(
    UUID orderId,
    UUID saleId,
    String userId,
    BigDecimal amount
) {}
