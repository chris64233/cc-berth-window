package com.chris64233.berthwindow.web.dto;

import com.chris64233.berthwindow.domain.BerthReservation;

import java.time.Instant;

public record ReservationResponse(String berthCode, String businessNo,
                                  Instant startTime, Instant endTime, int tugCount) {

    public static ReservationResponse from(BerthReservation reservation) {
        return new ReservationResponse(reservation.getBerth().getCode(),
                reservation.getApplication().getBusinessNo(),
                reservation.getStartTime(), reservation.getEndTime(), reservation.getTugCount());
    }
}
