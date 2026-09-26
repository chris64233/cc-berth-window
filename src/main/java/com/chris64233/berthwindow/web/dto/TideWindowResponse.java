package com.chris64233.berthwindow.web.dto;

import com.chris64233.berthwindow.domain.TideWindow;

import java.math.BigDecimal;
import java.time.Instant;

public record TideWindowResponse(Long id, Instant startTime, Instant endTime, BigDecimal maxDraft, long version) {

    public static TideWindowResponse from(TideWindow window) {
        return new TideWindowResponse(window.getId(), window.getStartTime(), window.getEndTime(),
                window.getMaxDraft(), window.getVersion());
    }
}
