package com.chris64233.berthwindow.web.dto;

import java.math.BigDecimal;
import java.time.Instant;

import com.chris64233.berthwindow.domain.ApplicationStatus;

public record ApplicationResponse(
        Long id,
        String applicationNo,
        String vesselCode,
        String vesselType,
        Instant eta,
        Instant etd,
        BigDecimal draft,
        String requiredBerthType,
        int requiredTugs,
        ApplicationStatus status,
        Long assignedBerthId,
        long version
) {
}
