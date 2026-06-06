package com.flashsale.sale.controller;

import com.flashsale.sale.entity.Sale;
import com.flashsale.sale.service.SaleService;
import com.flashsale.sale.service.StockService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/sales")
public class SaleController {

    private final SaleService saleService;
    private final StockService stockService;

    public SaleController(SaleService saleService, StockService stockService) {
        this.saleService = saleService;
        this.stockService = stockService;
    }

    @PostMapping
    public ResponseEntity<Sale> createSale(@RequestBody CreateSaleRequest request) {
        return ResponseEntity.status(201).body(saleService.createSale(request));
    }

    @PostMapping("/{saleId}/buy")
    public ResponseEntity<BuyResponse> buy(
            @PathVariable UUID saleId,
            @RequestHeader("X-User-ID") String userId,
            @RequestBody BuyRequest request) {
        return ResponseEntity.ok(saleService.buy(saleId, userId, request));
    }

    @GetMapping("/{saleId}/status")
    public ResponseEntity<SaleStatusResponse> getStatus(@PathVariable UUID saleId) {
        Sale sale = saleService.getSale(saleId);
        Long stock = stockService.getCurrentStock(saleId);
        return ResponseEntity.ok(new SaleStatusResponse(sale.getStatus(), stock, sale.getEndsAt()));
    }

    @PatchMapping("/{saleId}/restock")
    public ResponseEntity<Void> restock(
            @PathVariable UUID saleId,
            @RequestParam int quantity) {
        saleService.restock(saleId, quantity);
        return ResponseEntity.ok().build();
    }
}
