package com.chris64233.berthwindow.web.dto;

import com.chris64233.berthwindow.domain.BerthApplication;

import java.math.BigDecimal;
import java.time.Instant;

public record ApplicationResponse(
        String businessNo,
        String shipName,
        String shipType,
        BigDecimal draft,
        Instant expectedArrival,
        Instant expectedDeparture,
        int requiredTugs,
        String status,
        String berthCode,
        String rejectReason,
        long version) {

    public static ApplicationResponse from(BerthApplication app) {
        return new ApplicationResponse(app.getBusinessNo(), app.getShipName(), app.getShipType(), app.getDraft(),
                app.getExpectedArrival(), app.getExpectedDeparture(), app.getRequiredTugs(),
                app.getStatus().name(), app.getBerthCode(), app.getRejectReason(), app.getVersion());
    }
}
