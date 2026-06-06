package com.flashsale.waitlist.controller;

import com.flashsale.waitlist.service.WaitlistService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/waitlist")
public class WaitlistController {

    private final WaitlistService waitlistService;

    public WaitlistController(WaitlistService waitlistService) {
        this.waitlistService = waitlistService;
    }

    @PostMapping("/{saleId}/join")
    public ResponseEntity<WaitlistJoinResponse> join(
            @PathVariable UUID saleId,
            @RequestHeader("X-User-ID") String userId,
            @RequestBody WaitlistJoinRequest request) {
        WaitlistJoinResponse response = waitlistService.join(saleId, userId, request.email());
        return ResponseEntity.ok(response);
    }

    @GetMapping("/{saleId}/position")
    public ResponseEntity<WaitlistPositionResponse> position(
            @PathVariable UUID saleId,
            @RequestHeader("X-User-ID") String userId) {
        return ResponseEntity.ok(waitlistService.getPosition(saleId, userId));
    }

    @PostMapping("/{saleId}/notify")
    public ResponseEntity<String> notifyWaiters(
            @PathVariable UUID saleId,
            @RequestParam(defaultValue = "10") int quantity) {
        waitlistService.notifyTopWaiters(saleId, quantity);
        return ResponseEntity.ok("Notified top " + quantity + " waitlisted users");
    }
}
