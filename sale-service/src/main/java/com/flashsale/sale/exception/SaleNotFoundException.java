package com.flashsale.sale.exception;

import java.util.UUID;

public class SaleNotFoundException extends RuntimeException {
    public SaleNotFoundException(UUID saleId) { super("Sale not found: " + saleId); }
}
