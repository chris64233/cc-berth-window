package com.chris64233.berthwindow.repo;

import java.util.Optional;

import jakarta.persistence.LockModeType;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.chris64233.berthwindow.domain.BerthSwapProposal;

public interface BerthSwapProposalRepository extends JpaRepository<BerthSwapProposal, Long> {

    Optional<BerthSwapProposal> findBySwapNo(String swapNo);

    /** 对方案行加写锁：并发确认在此串行化，配合 @Version 保证只交换一次 */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from BerthSwapProposal p where p.swapNo = :swapNo")
    Optional<BerthSwapProposal> findWithLockBySwapNo(@Param("swapNo") String swapNo);
}
