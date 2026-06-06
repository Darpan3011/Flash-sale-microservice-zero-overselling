package com.flashsale.events;

import java.math.BigDecimal;
import java.util.UUID;

public record OrderRequestEvent(
    UUID orderId,
    UUID saleId,
    String userId,
    String productId,
    BigDecimal amount,
    String idempotencyKey
) {}
