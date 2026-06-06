package com.flashsale.sale.exception;

import java.util.UUID;

public class SoldOutException extends RuntimeException {
    private final UUID saleId;
    public SoldOutException(UUID saleId) { super("Item is sold out"); this.saleId = saleId; }
    public UUID getSaleId() { return saleId; }
}
