package com.chris64233.berthwindow.web.dto;

import java.time.Instant;

import com.chris64233.berthwindow.domain.BerthActionType;

public record TugAssignmentResponse(
        Long id,
        Long tugId,
        String tugCode,
        Long applicationId,
        String applicationNo,
        BerthActionType actionType,
        Instant actionTime
) {
}
