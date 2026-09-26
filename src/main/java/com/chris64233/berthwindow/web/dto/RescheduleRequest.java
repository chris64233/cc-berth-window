package com.chris64233.berthwindow.web.dto;

import java.time.Instant;

import jakarta.validation.constraints.NotNull;

/**
 * 改期请求：指定新的预计靠/离泊时间。
 */
public record RescheduleRequest(
        @NotNull Instant newEta,
        @NotNull Instant newEtd
) {
}
