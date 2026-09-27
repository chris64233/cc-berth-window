package com.chris64233.berthwindow.repo;

import java.util.Optional;

import jakarta.persistence.LockModeType;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.chris64233.berthwindow.domain.SwapProposal;

public interface SwapProposalRepository extends JpaRepository<SwapProposal, Long> {

    Optional<SwapProposal> findByProposalNo(String proposalNo);

    /** 对方案行加写锁：同一方案的并发确认在此串行化，杜绝重复交换/重复释放资源 */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from SwapProposal p where p.id = :id")
    Optional<SwapProposal> findWithLockById(@Param("id") Long id);
}
