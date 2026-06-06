package com.flashsale.sale.controller;
import java.math.BigDecimal;
import java.time.LocalDateTime;
public record CreateSaleRequest(String title, String productId, String productName, BigDecimal price, int totalStock, int maxPerUser, LocalDateTime startsAt, LocalDateTime endsAt) {}
