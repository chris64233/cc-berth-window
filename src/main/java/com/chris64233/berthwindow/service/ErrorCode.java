package com.chris64233.berthwindow.service;

import org.springframework.http.HttpStatus;

public enum ErrorCode {
    VALIDATION_ERROR(HttpStatus.BAD_REQUEST),
    BERTH_NOT_FOUND(HttpStatus.NOT_FOUND),
    TIDE_WINDOW_NOT_FOUND(HttpStatus.NOT_FOUND),
    APPLICATION_NOT_FOUND(HttpStatus.NOT_FOUND),
    PORT_CONFIG_MISSING(HttpStatus.CONFLICT),
    DUPLICATE_BUSINESS_NO(HttpStatus.CONFLICT),
    DUPLICATE_BERTH_CODE(HttpStatus.CONFLICT),
    STALE_APPLICATION(HttpStatus.CONFLICT),
    INVALID_STATE(HttpStatus.CONFLICT),
    TIDE_WINDOW_MISSING(HttpStatus.UNPROCESSABLE_ENTITY),
    BERTH_CAPACITY_EXCEEDED(HttpStatus.UNPROCESSABLE_ENTITY),
    TUG_CAPACITY_EXCEEDED(HttpStatus.UNPROCESSABLE_ENTITY),
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR);

    private final HttpStatus status;

    ErrorCode(HttpStatus status) {
        this.status = status;
    }

    public HttpStatus getStatus() {
        return status;
    }
}
