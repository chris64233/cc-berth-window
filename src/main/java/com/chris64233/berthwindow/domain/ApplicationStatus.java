package com.chris64233.berthwindow.domain;

public enum ApplicationStatus {
    /** 已提交，等待审批 */
    PENDING,
    /** 审批通过，泊位时间与拖轮均已占用 */
    APPROVED,
    /** 已取消（批准后、作业开始前取消，占用与拖轮安排已释放） */
    CANCELLED
}
