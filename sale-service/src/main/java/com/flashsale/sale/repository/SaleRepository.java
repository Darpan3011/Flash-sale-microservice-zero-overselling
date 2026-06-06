package com.flashsale.sale.repository;

import com.flashsale.sale.entity.Sale;
import com.flashsale.sale.entity.SaleStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Repository
public interface SaleRepository extends JpaRepository<Sale, UUID> {
    List<Sale> findByStatusAndStartsAtBefore(SaleStatus status, LocalDateTime time);
    List<Sale> findByStatusInAndEndsAtBefore(List<SaleStatus> statuses, LocalDateTime time);
}
