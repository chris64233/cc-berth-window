package com.chris64233.berthwindow.web.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 互换方案冻结请求：业务方案号（幂等键）与互换双方申请业务号。
 */
public record SwapProposalRequest(
        @NotBlank String proposalNo,
        @NotBlank String applicationANo,
        @NotBlank String applicationBNo
) {
}
