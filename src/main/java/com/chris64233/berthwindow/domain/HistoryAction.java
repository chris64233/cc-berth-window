package com.chris64233.berthwindow.domain;

public enum HistoryAction {
    SUBMITTED,
    APPROVED,
    RESCHEDULED,
    CANCELLED,
    /** 泊位时段互换确认成功 */
    SWAPPED,
    /** 泊位时段互换方案确认失败（含校验失败与版本变化失效） */
    SWAP_FAILED
}
