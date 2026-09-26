package com.chris64233.berthwindow.service;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.chris64233.berthwindow.domain.BerthApplication;
import com.chris64233.berthwindow.repo.BerthApplicationRepository;

/**
 * 申请插入网关：用独立事务（REQUIRES_NEW）执行 insert，
 * 使并发提交同一业务号触发的 application_no 唯一约束冲突可以被外层提交逻辑捕获，
 * 随后在外层事务中重读既有申请实现幂等，而不会污染外层事务。
 */
@Component
public class ApplicationInsertGateway {

    private final BerthApplicationRepository applicationRepository;

    public ApplicationInsertGateway(BerthApplicationRepository applicationRepository) {
        this.applicationRepository = applicationRepository;
    }

    /**
     * @throws DataIntegrityViolationException 业务号唯一约束冲突时抛出（内部事务已回滚）
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public BerthApplication insert(BerthApplication application) {
        return applicationRepository.saveAndFlush(application);
    }
}
