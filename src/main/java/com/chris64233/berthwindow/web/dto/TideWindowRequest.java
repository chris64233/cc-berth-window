package com.chris64233.berthwindow.web.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.time.Instant;

public record TideWindowRequest(
        @NotNull Instant startTime,
        @NotNull Instant endTime,
        @NotNull @DecimalMin(value = "0.0", inclusive = false) BigDecimal maxDraft) {
}
