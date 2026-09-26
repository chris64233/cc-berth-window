package com.chris64233.berthwindow.web.dto;

import com.chris64233.berthwindow.domain.ChangeHistory;

import java.time.Instant;

public record HistoryResponse(String changeType, String detail, Instant occurredAt) {

    public static HistoryResponse from(ChangeHistory history) {
        return new HistoryResponse(history.getChangeType().name(), history.getDetail(), history.getOccurredAt());
    }
}
