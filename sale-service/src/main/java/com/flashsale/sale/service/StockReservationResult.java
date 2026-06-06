package com.flashsale.sale.service;

public record StockReservationResult(
    boolean success,
    RejectReason reason,
    long remaining
) {
    public enum RejectReason { SOLD_OUT, LIMIT_EXCEEDED, SALE_NOT_ACTIVE }

    public static StockReservationResult success(long remaining) {
        return new StockReservationResult(true, null, remaining);
    }
    public static StockReservationResult soldOut() {
        return new StockReservationResult(false, RejectReason.SOLD_OUT, 0);
    }
    public static StockReservationResult limitExceeded() {
        return new StockReservationResult(false, RejectReason.LIMIT_EXCEEDED, 0);
    }
}
