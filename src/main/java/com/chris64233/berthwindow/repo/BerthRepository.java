package com.chris64233.berthwindow.repo;

import com.chris64233.berthwindow.domain.Berth;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

public interface BerthRepository extends JpaRepository<Berth, Long> {

    Optional<Berth> findByCode(String code);

    List<Berth> findByBerthTypeAndMaxDraftGreaterThanEqualOrderByCode(String berthType, BigDecimal maxDraft);

    /**
     * 审批/改期时对泊位行加悲观写锁，串行化同一泊位上的容量决策。
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select b from Berth b where b.id = :id")
    Optional<Berth> lockById(@Param("id") Long id);
}
