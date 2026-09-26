package com.chris64233.berthwindow.web.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.time.Instant;

public record ApplicationRequest(
        @NotBlank String businessNo,
        @NotBlank String shipName,
        @NotBlank String shipType,
        @NotNull @DecimalMin(value = "0.0", inclusive = false) BigDecimal draft,
        @NotNull Instant expectedArrival,
        @NotNull Instant expectedDeparture,
        @Min(0) int requiredTugs) {
}
