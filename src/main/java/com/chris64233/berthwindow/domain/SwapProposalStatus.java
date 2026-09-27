package com.chris64233.berthwindow.domain;

/**
 * 泊位互换方案状态。
 *
 * <ul>
 *   <li>{@link #PROPOSED}：方案已冻结双方泊位、时间、潮汐资料与拖轮安排，等待确认；</li>
 *   <li>{@link #CONFIRMED}：双方已在同一事务内完成交换，资源占用已原子切换；</li>
 *   <li>{@link #FAILED}：确认时重校验未通过（或依据数据已变化），原两份批准安排保持不变。</li>
 * </ul>
 */
public enum SwapProposalStatus {
    PROPOSED,
    CONFIRMED,
    FAILED
}
