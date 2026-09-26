package com.chris64233.berthwindow.web.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Set;

public record BerthResponse(
        Long id,
        String code,
        String name,
        String berthType,
        Set<String> acceptedVesselTypes,
        BigDecimal maxDraft,
        int simultaneousCapacity,
        long version
) {
}
