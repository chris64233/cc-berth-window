package com.chris64233.berthwindow.web.dto;

import java.math.BigDecimal;
import java.time.Instant;

public record TideWindowResponse(
        Long id,
        String berthType,
        Instant windowStart,
        Instant windowEnd,
        BigDecimal availableDepth,
        long version
) {
}
