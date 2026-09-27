package com.chris64233.berthwindow.service;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.chris64233.berthwindow.domain.SwapProposal;
import com.chris64233.berthwindow.repo.SwapProposalRepository;

/**
 * 互换方案插入网关：用独立事务（REQUIRES_NEW）执行 insert，
 * 使并发提交同一方案号触发的 proposal_no 唯一约束冲突可被外层冻结逻辑捕获，
 * 随后重读既有方案实现幂等，而不会污染外层事务。
 */
@Component
public class SwapProposalInsertGateway {

    private final SwapProposalRepository proposalRepository;

    public SwapProposalInsertGateway(SwapProposalRepository proposalRepository) {
        this.proposalRepository = proposalRepository;
    }

    /**
     * @throws DataIntegrityViolationException 方案号唯一约束冲突时抛出（内部事务已回滚）
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public SwapProposal insert(SwapProposal proposal) {
        return proposalRepository.saveAndFlush(proposal);
    }
}
