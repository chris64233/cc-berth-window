package com.chris64233.berthwindow.web.dto;

import java.time.Instant;

import com.chris64233.berthwindow.domain.SwapStatus;

/**
 * 互换方案响应：冻结的双方安排快照、生命周期状态与失败信息。
 */
public record SwapProposalResponse(
        Long id,
        String proposalNo,
        String applicationANo,
        Long aBerthId,
        Instant aEta,
        Instant aEtd,
        long aAppVersion,
        String aTugIds,
        String applicationBNo,
        Long bBerthId,
        Instant bEta,
        Instant bEtd,
        long bAppVersion,
        String bTugIds,
        SwapStatus status,
        String failureCode,
        String failureReason,
        Instant createdAt,
        Instant finalizedAt
) {
}
