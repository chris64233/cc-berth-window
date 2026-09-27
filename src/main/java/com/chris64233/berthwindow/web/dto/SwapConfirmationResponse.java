package com.chris64233.berthwindow.web.dto;

/**
 * 互换确认结果：success=false 时 code/reason 给出失败错误码与原因（方案已落库 FAILED，原安排可用）。
 */
public record SwapConfirmationResponse(
        boolean success,
        String proposalNo,
        String status,
        String code,
        String reason,
        ApplicationResponse applicationA,
        ApplicationResponse applicationB
) {
}
