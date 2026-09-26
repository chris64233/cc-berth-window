package com.chris64233.berthwindow.web;

import java.time.Instant;
import java.util.List;

/**
 * 统一错误响应体：{@code {timestamp, code, message, details}}。
 */
public record ErrorResponse(
        Instant timestamp,
        String code,
        int status,
        String message,
        List<String> details
) {
    public static ErrorResponse of(ErrorCode code, String message) {
        return new ErrorResponse(Instant.now(), code.name(), code.getStatus(), message, List.of());
    }

    public static ErrorResponse of(ErrorCode code, String message, List<String> details) {
        return new ErrorResponse(Instant.now(), code.name(), code.getStatus(), message, details);
    }
}
