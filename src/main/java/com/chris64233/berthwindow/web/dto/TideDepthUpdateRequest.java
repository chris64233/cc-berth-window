package com.chris64233.berthwindow.web.dto;

import java.math.BigDecimal;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

public record TideDepthUpdateRequest(
        @NotNull @Positive BigDecimal availableDepth,
        Long expectedVersion
) {
}
