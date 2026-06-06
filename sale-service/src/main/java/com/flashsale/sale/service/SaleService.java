package com.flashsale.sale.service;

import com.flashsale.events.OrderRequestEvent;
import com.flashsale.events.SaleRestockedEvent;
import com.flashsale.sale.controller.BuyRequest;
import com.flashsale.sale.controller.BuyResponse;
import com.flashsale.sale.controller.CreateSaleRequest;
import com.flashsale.sale.entity.Sale;
import com.flashsale.sale.entity.SaleStatus;
import com.flashsale.sale.exception.*;
import com.flashsale.sale.repository.SaleRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Service
@Slf4j
public class SaleService {

    private final SaleRepository saleRepo;
    private final StockService stockService;
    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final StringRedisTemplate redis;

    public SaleService(SaleRepository saleRepo, StockService stockService, KafkaTemplate<String, Object> kafkaTemplate, StringRedisTemplate redis) {
        this.saleRepo = saleRepo;
        this.stockService = stockService;
        this.kafkaTemplate = kafkaTemplate;
        this.redis = redis;
    }

    public Sale createSale(CreateSaleRequest request) {
        Sale sale = new Sale();
        sale.setTitle(request.title());
        sale.setProductId(request.productId());
        sale.setProductName(request.productName());
        sale.setPrice(request.price());
        sale.setTotalStock(request.totalStock());
        sale.setMaxPerUser(request.maxPerUser());
        sale.setStartsAt(request.startsAt());
        sale.setEndsAt(request.endsAt());
        sale.setStatus(SaleStatus.SCHEDULED);
        return saleRepo.save(sale);
    }

    public Sale getSale(UUID saleId) {
        return saleRepo.findById(saleId).orElseThrow(() -> new SaleNotFoundException(saleId));
    }

    public BuyResponse buy(UUID saleId, String userId, BuyRequest request) {
        Sale sale = getSale(saleId);

        if (sale.getStatus() != SaleStatus.ACTIVE) {
            throw new SaleNotActiveException(sale.getStatus());
        }

        LocalDateTime now = LocalDateTime.now();
        if (now.isBefore(sale.getStartsAt()) || now.isAfter(sale.getEndsAt())) {
            throw new SaleWindowClosedException();
        }

        StockReservationResult result = stockService.tryReserve(
            saleId, userId, sale.getMaxPerUser()
        );

        if (!result.success()) {
            if (result.reason() == StockReservationResult.RejectReason.SOLD_OUT) {
                if (stockService.getCurrentStock(saleId) <= 0) {
                    sale.setStatus(SaleStatus.SOLD_OUT);
                    saleRepo.save(sale);
                }
                throw new SoldOutException(saleId);
            }
            if (result.reason() == StockReservationResult.RejectReason.LIMIT_EXCEEDED) {
                throw new PurchaseLimitExceededException(sale.getMaxPerUser());
            }
        }

        String idempotencyKey = saleId + ":" + userId + ":" + System.currentTimeMillis();
        OrderRequestEvent event = new OrderRequestEvent(
            UUID.randomUUID(),
            saleId,
            userId,
            sale.getProductId(),
            sale.getPrice(),
            idempotencyKey
        );
        kafkaTemplate.send("sale.order.requested", saleId.toString(), event);

        return new BuyResponse(event.orderId(), "Order queued successfully", result.remaining());
    }

    @Scheduled(fixedDelay = 10000)
    public void activatePendingSales() {
        LocalDateTime now = LocalDateTime.now();
        List<Sale> toActivate = saleRepo.findByStatusAndStartsAtBefore(SaleStatus.SCHEDULED, now);
        for (Sale sale : toActivate) {
            sale.setStatus(SaleStatus.ACTIVE);
            saleRepo.save(sale);
            stockService.initializeStock(sale.getId(), sale.getTotalStock());
            log.info("Sale {} activated with stock {}", sale.getId(), sale.getTotalStock());
        }
    }

    @Scheduled(fixedDelay = 10000)
    public void endExpiredSales() {
        LocalDateTime now = LocalDateTime.now();
        List<Sale> toEnd = saleRepo.findByStatusInAndEndsAtBefore(
            List.of(SaleStatus.ACTIVE, SaleStatus.SOLD_OUT), now
        );
        toEnd.forEach(sale -> {
            sale.setStatus(SaleStatus.ENDED);
            saleRepo.save(sale);
        });
    }

    public void restock(UUID saleId, int quantity) {
        Sale sale = getSale(saleId);
        sale.setTotalStock(sale.getTotalStock() + quantity);
        if (sale.getStatus() == SaleStatus.SOLD_OUT) {
            sale.setStatus(SaleStatus.ACTIVE);
        }
        saleRepo.save(sale);
        redis.opsForValue().increment("stock:" + saleId, quantity);
        kafkaTemplate.send("sale.restocked", new SaleRestockedEvent(saleId, quantity));
    }
}
