package com.flashsale.sale.exception;

public class PurchaseLimitExceededException extends RuntimeException {
    private final int limit;
    public PurchaseLimitExceededException(int limit) { super("Purchase limit exceeded"); this.limit = limit; }
    public int getLimit() { return limit; }
}
