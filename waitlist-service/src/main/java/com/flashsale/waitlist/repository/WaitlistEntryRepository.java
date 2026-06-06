package com.flashsale.waitlist.repository;

import com.flashsale.waitlist.entity.WaitlistEntry;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface WaitlistEntryRepository extends JpaRepository<WaitlistEntry, UUID> {
    Optional<WaitlistEntry> findBySaleIdAndUserId(UUID saleId, String userId);
}
