package com.chris64233.berthwindow.domain;

public enum HistoryAction {
    SUBMITTED,
    APPROVED,
    RESCHEDULED,
    CANCELLED,
    /** 互换方案已冻结（含双方互换前后安排快照） */
    SWAP_PROPOSED,
    /** 互换确认成功，两份安排与资源占用已原子切换 */
    SWAP_CONFIRMED,
    /** 互换确认失败（含失败原因），原安排保持不变 */
    SWAP_FAILED
}
