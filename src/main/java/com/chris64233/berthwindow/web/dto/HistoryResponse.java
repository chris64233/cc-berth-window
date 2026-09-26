package com.chris64233.berthwindow.web.dto;

import java.time.Instant;

import com.chris64233.berthwindow.domain.HistoryAction;

public record HistoryResponse(
        Long id,
        Long applicationId,
        String applicationNo,
        HistoryAction action,
        String detail,
        Instant occurredAt
) {
}
