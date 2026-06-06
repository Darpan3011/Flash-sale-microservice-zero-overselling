package com.flashsale.order.repository;

import com.flashsale.order.entity.Order;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface OrderRepository extends JpaRepository<Order, UUID> {
    boolean existsByIdempotencyKey(String idempotencyKey);
    List<Order> findByUserId(String userId);
}
