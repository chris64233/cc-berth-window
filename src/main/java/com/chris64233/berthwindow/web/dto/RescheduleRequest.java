package com.chris64233.berthwindow.web.dto;

import jakarta.validation.constraints.NotNull;

import java.time.Instant;

public record RescheduleRequest(
        @NotNull Instant expectedArrival,
        @NotNull Instant expectedDeparture,
        Long expectedVersion) {
}
