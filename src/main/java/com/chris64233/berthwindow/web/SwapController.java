package com.chris64233.berthwindow.web;

import java.util.List;

import jakarta.validation.Valid;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.chris64233.berthwindow.service.BerthSwapService;
import com.chris64233.berthwindow.web.dto.SwapConfirmationResponse;
import com.chris64233.berthwindow.web.dto.SwapProposalRequest;
import com.chris64233.berthwindow.web.dto.SwapProposalResponse;

/**
 * 两艘已批准船舶的泊位时段互换：
 * 先冻结方案（双方泊位、时间、潮汐版本、拖轮安排），再按交换后的条件确认。
 */
@RestController
@RequestMapping("/api/swaps")
public class SwapController {

    private final BerthSwapService swapService;

    public SwapController(BerthSwapService swapService) {
        this.swapService = swapService;
    }

    /** 冻结互换方案。同一 proposalNo 重复冻结且参与方一致时幂等返回。 */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public SwapProposalResponse propose(@Valid @RequestBody SwapProposalRequest request) {
        return DtoMapper.swapProposal(swapService.propose(
                request.proposalNo(), request.applicationANo(), request.applicationBNo()));
    }

    /**
     * 确认互换：单事务内对交换后的泊位/时段重新校验泊位条件、潮汐与拖轮，
     * 全部满足才原子切换；任一不满足返回错误（统一错误响应），方案落库 FAILED，
     * 两份原安排保持可用。重复确认同一方案幂等：已成功返回交换后安排，
     * 已失败返回与首次相同的错误码与原因。
     */
    @PostMapping("/{proposalNo}/confirm")
    public SwapConfirmationResponse confirm(@PathVariable String proposalNo) {
        BerthSwapService.Confirmation result = swapService.confirm(proposalNo);
        if (!result.success()) {
            throw new ApiException(result.code(),
                    "互换方案 " + proposalNo + " 确认失败，双方原安排保持可用：" + result.reason());
        }
        return new SwapConfirmationResponse(true, result.proposal().getProposalNo(),
                result.proposal().getStatus().name(), null, null,
                DtoMapper.application(result.applicationA()),
                DtoMapper.application(result.applicationB()));
    }

    @GetMapping
    public List<SwapProposalResponse> list() {
        return swapService.listProposals().stream().map(DtoMapper::swapProposal).toList();
    }

    @GetMapping("/{proposalNo}")
    public SwapProposalResponse get(@PathVariable String proposalNo) {
        return DtoMapper.swapProposal(swapService.getProposal(proposalNo));
    }
}
