package com.flashsale.sale.exception;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(SoldOutException.class)
    public ResponseEntity<ErrorResponse> handleSoldOut(SoldOutException e) {
        return ResponseEntity.status(409)
            .body(new ErrorResponse("SOLD_OUT", "Item is sold out", e.getSaleId()));
    }

    @ExceptionHandler(PurchaseLimitExceededException.class)
    public ResponseEntity<ErrorResponse> handleLimitExceeded(PurchaseLimitExceededException e) {
        return ResponseEntity.status(429)
            .body(new ErrorResponse("LIMIT_EXCEEDED",
                "You can only buy " + e.getLimit() + " per sale", null));
    }

    @ExceptionHandler(SaleNotActiveException.class)
    public ResponseEntity<ErrorResponse> handleNotActive(SaleNotActiveException e) {
        return ResponseEntity.status(400)
            .body(new ErrorResponse("SALE_NOT_ACTIVE",
                "Sale is currently " + e.getStatus(), null));
    }
    
    @ExceptionHandler(SaleWindowClosedException.class)
    public ResponseEntity<ErrorResponse> handleWindowClosed(SaleWindowClosedException e) {
        return ResponseEntity.status(400)
            .body(new ErrorResponse("SALE_WINDOW_CLOSED", e.getMessage(), null));
    }
    
    @ExceptionHandler(SaleNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleNotFound(SaleNotFoundException e) {
        return ResponseEntity.status(404)
            .body(new ErrorResponse("SALE_NOT_FOUND", e.getMessage(), null));
    }
}
