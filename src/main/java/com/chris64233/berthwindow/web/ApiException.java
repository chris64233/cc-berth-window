package com.chris64233.berthwindow.web;

/**
 * 业务异常，携带统一错误码与可展示信息。
 */
public class ApiException extends RuntimeException {

    private final ErrorCode code;

    public ApiException(ErrorCode code, String message) {
        super(message);
        this.code = code;
    }

    public ErrorCode getCode() {
        return code;
    }
}
