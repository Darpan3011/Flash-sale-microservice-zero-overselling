package com.flashsale.waitlist.service;

import com.flashsale.events.WaitlistNotifyEvent;
import com.flashsale.waitlist.controller.WaitlistJoinResponse;
import com.flashsale.waitlist.controller.WaitlistPositionResponse;
import com.flashsale.waitlist.entity.WaitlistEntry;
import com.flashsale.waitlist.repository.WaitlistEntryRepository;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations.TypedTuple;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class WaitlistService {

    private final StringRedisTemplate redis;
    private final WaitlistEntryRepository waitlistRepo;
    private final KafkaTemplate<String, Object> kafkaTemplate;

    private static final String WAITLIST_KEY = "waitlist:%s";

    public WaitlistService(StringRedisTemplate redis, WaitlistEntryRepository waitlistRepo, KafkaTemplate<String, Object> kafkaTemplate) {
        this.redis = redis;
        this.waitlistRepo = waitlistRepo;
        this.kafkaTemplate = kafkaTemplate;
    }

    public WaitlistJoinResponse join(UUID saleId, String userId, String email) {
        String key = String.format(WAITLIST_KEY, saleId);

        Double score = redis.opsForZSet().score(key, userId);
        if (score != null) {
            Long rank = redis.opsForZSet().rank(key, userId);
            long position = rank != null ? rank + 1 : 1;
            return new WaitlistJoinResponse(false, "Already on waitlist", position);
        }

        double joinTime = System.currentTimeMillis();
        redis.opsForZSet().add(key, userId, joinTime);

        WaitlistEntry entry = new WaitlistEntry(saleId, userId, email, LocalDateTime.now());
        waitlistRepo.save(entry);

        Long rank = redis.opsForZSet().rank(key, userId);
        long position = rank != null ? rank + 1 : 1;

        return new WaitlistJoinResponse(true, "Added to waitlist", position);
    }

    public WaitlistPositionResponse getPosition(UUID saleId, String userId) {
        String key = String.format(WAITLIST_KEY, saleId);
        Long rank = redis.opsForZSet().rank(key, userId);
        if (rank == null) return new WaitlistPositionResponse(false, 0, 0);

        Long total = redis.opsForZSet().size(key);
        long totalWaiting = total != null ? total : 0;
        return new WaitlistPositionResponse(true, rank + 1, totalWaiting);
    }

    public void notifyTopWaiters(UUID saleId, int quantity) {
        String key = String.format(WAITLIST_KEY, saleId);

        Set<TypedTuple<String>> tuples = redis.opsForZSet().popMin(key, quantity);
        if (tuples == null || tuples.isEmpty()) return;

        Set<String> topUsers = tuples.stream()
            .map(TypedTuple::getValue)
            .collect(Collectors.toSet());

        for (String userId : topUsers) {
            WaitlistEntry entry = waitlistRepo.findBySaleIdAndUserId(saleId, userId)
                .orElse(null);
            if (entry != null) {
                entry.setNotified(true);
                waitlistRepo.save(entry);
                kafkaTemplate.send("waitlist.notify",
                    new WaitlistNotifyEvent(saleId, userId, entry.getEmail()));
            }
        }
    }
}
