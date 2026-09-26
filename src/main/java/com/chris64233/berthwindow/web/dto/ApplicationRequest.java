package com.chris64233.berthwindow.web.dto;

import java.math.BigDecimal;
import java.time.Instant;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;

public record ApplicationRequest(
        @NotBlank String applicationNo,
        @NotBlank String vesselCode,
        @NotBlank String vesselType,
        @NotNull Instant eta,
        @NotNull Instant etd,
        @NotNull @Positive BigDecimal draft,
        @NotBlank String requiredBerthType,
        @PositiveOrZero int requiredTugs
) {
}
