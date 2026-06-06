package com.flashsale.sale.service;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.UUID;

@Service
public class StockService {

    private final StringRedisTemplate redis;

    private static final String STOCK_KEY      = "stock:%s";
    private static final String PURCHASED_KEY  = "purchased:%s:%s";

    public StockService(StringRedisTemplate redis) {
        this.redis = redis;
    }

    public void initializeStock(UUID saleId, int stock) {
        redis.opsForValue().set(stockKey(saleId), String.valueOf(stock));
    }

    public StockReservationResult tryReserve(UUID saleId, String userId, int maxPerUser) {
        String purchasedKey = String.format(PURCHASED_KEY, saleId, userId);
        Long userCount = redis.opsForValue().increment(purchasedKey);
        redis.expire(purchasedKey, Duration.ofDays(1));

        if (userCount != null && userCount > maxPerUser) {
            redis.opsForValue().decrement(purchasedKey);
            return StockReservationResult.limitExceeded();
        }

        Long remaining = redis.opsForValue().decrement(stockKey(saleId));

        if (remaining != null && remaining >= 0) {
            return StockReservationResult.success(remaining);
        }

        redis.opsForValue().increment(stockKey(saleId));
        redis.opsForValue().decrement(purchasedKey);
        return StockReservationResult.soldOut();
    }

    public void releaseStock(UUID saleId, String userId) {
        redis.opsForValue().increment(stockKey(saleId));
        String purchasedKey = String.format(PURCHASED_KEY, saleId, userId);
        redis.opsForValue().decrement(purchasedKey);
    }

    public Long getCurrentStock(UUID saleId) {
        String val = redis.opsForValue().get(stockKey(saleId));
        return val != null ? Long.parseLong(val) : 0L;
    }

    private String stockKey(UUID saleId) {
        return String.format(STOCK_KEY, saleId);
    }
}
