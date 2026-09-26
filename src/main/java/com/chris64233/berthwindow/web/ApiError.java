package com.chris64233.berthwindow.web;

import java.time.Instant;

/**
 * 统一错误响应体。
 */
public record ApiError(String code, String message, String path, Instant timestamp) {
}
