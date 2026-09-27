package com.chris64233.berthwindow.repo;

import java.util.Optional;

import jakarta.persistence.LockModeType;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.chris64233.berthwindow.domain.BerthApplication;

public interface BerthApplicationRepository extends JpaRepository<BerthApplication, Long> {

    Optional<BerthApplication> findByApplicationNo(String applicationNo);

    /** 对申请行加写锁，串行化同一申请的并发审批/改期 */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from BerthApplication a where a.applicationNo = :applicationNo")
    Optional<BerthApplication> findWithLockByApplicationNo(@Param("applicationNo") String applicationNo);

    /** 互换确认时按 id 锁定申请行（始终先锁小 id，避免跨事务死锁） */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from BerthApplication a where a.id = :id")
    Optional<BerthApplication> findWithLockById(@Param("id") Long id);
}
