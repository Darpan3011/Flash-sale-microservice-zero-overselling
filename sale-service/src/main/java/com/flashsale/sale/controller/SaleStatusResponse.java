package com.flashsale.sale.controller;
import com.flashsale.sale.entity.SaleStatus;
import java.time.LocalDateTime;
public record SaleStatusResponse(SaleStatus status, long stock, LocalDateTime endsAt) {}
