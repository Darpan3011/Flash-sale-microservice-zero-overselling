package com.flashsale.sale.exception;

import java.util.UUID;

public record ErrorResponse(String code, String message, UUID saleId) {}
