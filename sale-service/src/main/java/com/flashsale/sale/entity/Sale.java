package com.flashsale.sale.entity;

import jakarta.persistence.*;
import lombok.Data;
import org.springframework.data.annotation.CreatedBy;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedBy;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "sales")
@EntityListeners(AuditingEntityListener.class)
@Data
public class Sale {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    private String title;
    private String productId;
    private String productName;
    private BigDecimal price;
    private Integer totalStock;
    private Integer maxPerUser;
    private LocalDateTime startsAt;
    private LocalDateTime endsAt;

    @Enumerated(EnumType.STRING)
    private SaleStatus status = SaleStatus.SCHEDULED;

    @CreatedDate
    private LocalDateTime createdAt;
    
    @LastModifiedDate
    private LocalDateTime updatedAt;

    @CreatedBy
    private String createdBy;

    @LastModifiedBy
    private String lastModifiedBy;
}
