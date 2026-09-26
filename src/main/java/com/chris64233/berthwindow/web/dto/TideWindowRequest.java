package com.chris64233.berthwindow.web.dto;

import java.math.BigDecimal;
import java.time.Instant;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

public record TideWindowRequest(
        @NotBlank String berthType,
        @NotNull Instant windowStart,
        @NotNull Instant windowEnd,
        @NotNull @Positive BigDecimal availableDepth
) {
}
