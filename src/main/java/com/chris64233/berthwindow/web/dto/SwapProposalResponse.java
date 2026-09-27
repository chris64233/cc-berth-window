package com.chris64233.berthwindow.web.dto;

import java.time.Instant;

import com.chris64233.berthwindow.domain.SwapProposalStatus;

public record SwapProposalResponse(
        Long id,
        String swapNo,
        String applicationANo,
        String applicationBNo,
        SwapProposalStatus status,
        String failureReason,
        Instant createdAt,
        Instant confirmedAt,
        long version
) {
}
