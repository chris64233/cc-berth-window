package com.chris64233.berthwindow.web.dto;

import java.math.BigDecimal;
import java.util.Set;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

public record BerthRequest(
        @NotBlank String code,
        @NotBlank String name,
        @NotBlank String berthType,
        @NotNull @Size(min = 1) Set<String> acceptedVesselTypes,
        @NotNull @Positive BigDecimal maxDraft,
        @Positive int simultaneousCapacity
) {
}
