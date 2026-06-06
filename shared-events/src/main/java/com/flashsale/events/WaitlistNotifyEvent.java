package com.flashsale.events;

import java.util.UUID;

public record WaitlistNotifyEvent(
    UUID saleId,
    String userId,
    String email
) {}
