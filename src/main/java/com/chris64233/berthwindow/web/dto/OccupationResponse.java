package com.chris64233.berthwindow.web.dto;

import java.time.Instant;

public record OccupationResponse(
        Long id,
        Long berthId,
        Long applicationId,
        String applicationNo,
        Instant startTime,
        Instant endTime
) {
}
