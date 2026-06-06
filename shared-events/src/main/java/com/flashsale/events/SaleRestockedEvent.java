package com.flashsale.events;

import java.util.UUID;

public record SaleRestockedEvent(
    UUID saleId,
    int quantity
) {}
