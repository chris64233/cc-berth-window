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
import com.chris64233.berthwindow.web.dto.SwapProposalRequest;
import com.chris64233.berthwindow.web.dto.SwapProposalResponse;

/**
 * 两艘已批准船舶的泊位时段互换：
 * 提议（冻结双方泊位/时间/潮汐/拖轮并按交换后条件预校验）与确认（原子切换，失败保留原安排）。
 */
@RestController
@RequestMapping("/api/swaps")
public class BerthSwapController {

    private final BerthSwapService swapService;

    public BerthSwapController(BerthSwapService swapService) {
        this.swapService = swapService;
    }

    /** 提议互换。同一 swapNo 同双方重复提议返回同一方案（幂等）。 */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public SwapProposalResponse propose(@Valid @RequestBody SwapProposalRequest request) {
        return DtoMapper.swapProposal(swapService.propose(
                request.swapNo(), request.applicationANo(), request.applicationBNo()));
    }

    /**
     * 确认互换：依据冻结快照重新校验，全部满足后原子更新两份安排与资源占用；
     * 任一方不满足或依据数据已变化，则原两份批准安排保持不变，方案标记 FAILED 并记录原因。
     */
    @PostMapping("/{swapNo}/confirm")
    public SwapProposalResponse confirm(@PathVariable String swapNo) {
        return DtoMapper.swapProposal(swapService.confirm(swapNo));
    }

    @GetMapping("/{swapNo}")
    public SwapProposalResponse get(@PathVariable String swapNo) {
        return DtoMapper.swapProposal(swapService.getProposal(swapNo));
    }

    @GetMapping
    public List<SwapProposalResponse> list() {
        return swapService.listProposals().stream().map(DtoMapper::swapProposal).toList();
    }
}
