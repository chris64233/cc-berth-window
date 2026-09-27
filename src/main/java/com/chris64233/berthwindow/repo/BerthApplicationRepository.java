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

    /** 对申请行加写锁（按 id），互换等涉及两份申请的场景按 id 升序加锁以避免死锁 */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from BerthApplication a where a.id = :id")
    Optional<BerthApplication> findWithLockById(@Param("id") Long id);
}
