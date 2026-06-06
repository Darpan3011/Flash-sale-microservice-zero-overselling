package com.flashsale.waitlist.entity;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "waitlist_entries", uniqueConstraints = {
    @UniqueConstraint(columnNames = {"saleId", "userId"})
})
@Data
@NoArgsConstructor
public class WaitlistEntry {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    private UUID saleId;
    private String userId;
    private String email;
    private LocalDateTime joinedAt = LocalDateTime.now();
    private boolean notified = false;

    public WaitlistEntry(UUID saleId, String userId, String email, LocalDateTime joinedAt) {
        this.saleId = saleId;
        this.userId = userId;
        this.email = email;
        this.joinedAt = joinedAt;
    }
}
