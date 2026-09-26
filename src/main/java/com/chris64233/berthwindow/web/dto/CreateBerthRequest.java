package com.chris64233.berthwindow.web.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

public record CreateBerthRequest(
        @NotBlank String code,
        @NotBlank String berthType,
        @NotNull @DecimalMin(value = "0.0", inclusive = false) BigDecimal maxDraft,
        @Min(1) int concurrentCapacity) {
}
