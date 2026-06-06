package com.flashsale.sale.exception;

import com.flashsale.sale.entity.SaleStatus;

public class SaleNotActiveException extends RuntimeException {
    private final SaleStatus status;
    public SaleNotActiveException(SaleStatus status) { super("Sale is not active"); this.status = status; }
    public SaleStatus getStatus() { return status; }
}
