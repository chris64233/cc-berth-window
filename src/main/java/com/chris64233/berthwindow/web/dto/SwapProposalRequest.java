package com.chris64233.berthwindow.web.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 互换提议请求：以 {@code swapNo} 为业务幂等键，指定两份已批准申请互换泊位时段。
 */
public record SwapProposalRequest(
        @NotBlank String swapNo,
        @NotBlank String applicationANo,
        @NotBlank String applicationBNo
) {
}
