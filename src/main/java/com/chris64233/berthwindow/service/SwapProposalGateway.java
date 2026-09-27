package com.chris64233.berthwindow.service;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.chris64233.berthwindow.domain.BerthSwapProposal;
import com.chris64233.berthwindow.repo.BerthSwapProposalRepository;

/**
 * 互换方案插入网关：以 REQUIRES_NEW 执行方案 insert，
 * 使并发同号提议触发的 swap_no 唯一约束冲突立即暴露给外层提议逻辑，
 * 随后外层重读既有方案实现幂等，而不会污染外层事务。
 */
@Component
public class SwapProposalGateway {

    private final BerthSwapProposalRepository proposalRepository;

    public SwapProposalGateway(BerthSwapProposalRepository proposalRepository) {
        this.proposalRepository = proposalRepository;
    }

    /**
     * @throws DataIntegrityViolationException swap_no 唯一约束冲突时抛出（内部事务已回滚）
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public BerthSwapProposal insert(BerthSwapProposal proposal) {
        return proposalRepository.saveAndFlush(proposal);
    }
}
