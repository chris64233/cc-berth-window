package com.chris64233.berthwindow.domain;

/**
 * 泊位时段互换方案的生命周期状态。
 */
public enum SwapStatus {
    /** 已冻结双方安排、等待确认 */
    PROPOSED,
    /** 确认成功，双方安排已原子交换 */
    CONFIRMED,
    /** 确认失败（校验不满足）或已因版本变化失效，原安排保持可用 */
    FAILED
}
