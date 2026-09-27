package com.chris64233.berthwindow.web;

/**
 * 统一业务错误码。所有错误经 {@link GlobalExceptionHandler} 转换为统一响应体。
 */
public enum ErrorCode {
    VALIDATION_FAILED(400),
    APPLICATION_NOT_FOUND(404),
    BERTH_NOT_FOUND(404),
    RESOURCE_NOT_FOUND(404),
    APPLICATION_ALREADY_APPROVED(409),
    APPLICATION_NOT_APPROVED(409),
    APPLICATION_CANCELLED(409),
    NO_MATCHING_BERTH(422),
    BERTH_CAPACITY_EXCEEDED(409),
    INSUFFICIENT_TUGS(409),
    TIDE_WINDOW_UNAVAILABLE(422),
    WINDOW_ALREADY_STARTED(409),
    /** 审批判断所依据的潮汐或申请数据在提交时已变化，判断结果失效 */
    JUDGMENT_STALE(409),
    DUPLICATE_BUSINESS_KEY(409),
    /** 互换方案不存在 */
    SWAP_NOT_FOUND(404),
    /** 互换方案已落定（已成功或已失败），不能重复确认 */
    SWAP_ALREADY_FINALIZED(409),
    /** 互换方案冻结的安排/潮汐资料已因版本变化失效 */
    SWAP_PROPOSAL_STALE(409),
    /** 互换双方不能是同一份申请 */
    SWAP_SAME_APPLICATION(400),
    INTERNAL_ERROR(500);

    private final int status;

    ErrorCode(int status) {
        this.status = status;
    }

    public int getStatus() {
        return status;
    }
}
