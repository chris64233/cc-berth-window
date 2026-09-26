package com.chris64233.berthwindow.repo;

import com.chris64233.berthwindow.domain.TideWindow;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface TideWindowRepository extends JpaRepository<TideWindow, Long> {

    List<TideWindow> findByBerth_IdOrderByStartTime(Long berthId);

    /**
     * 审批/改期期间锁定该泊位的全部潮汐窗口，
     * 防止潮汐数据在判断过程中被并发修改而导致使用旧判断结果。
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from TideWindow t where t.berth.id = :berthId order by t.startTime")
    List<TideWindow> lockByBerthId(@Param("berthId") Long berthId);
}
