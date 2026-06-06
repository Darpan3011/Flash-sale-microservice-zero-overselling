package com.flashsale.sale.controller;
import java.util.UUID;
public record BuyResponse(UUID orderId, String message, long remainingStock) {}
