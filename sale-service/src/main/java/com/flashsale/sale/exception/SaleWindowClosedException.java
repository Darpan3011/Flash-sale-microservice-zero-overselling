package com.flashsale.sale.exception;

public class SaleWindowClosedException extends RuntimeException {
    public SaleWindowClosedException() { super("Sale window is closed"); }
}
