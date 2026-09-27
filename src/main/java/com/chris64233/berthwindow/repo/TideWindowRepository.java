package com.chris64233.berthwindow.repo;

import java.util.Collection;
import java.util.List;

import jakarta.persistence.LockModeType;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.chris64233.berthwindow.domain.TideWindow;

public interface TideWindowRepository extends JpaRepository<TideWindow, Long> {

    List<TideWindow> findByBerthType(String berthType);

    List<TideWindow> findByBerthTypeIn(Collection<String> berthTypes);

    /**
     * 以 OPTIMISTIC（version 校验）方式读取潮汐窗口：提交时 Hibernate 会再次校验
     * 返回窗口的版本号，若审批期间任一窗口被修改，本次审批失败回滚，
     * 不允许使用过期的潮汐判断结果。
     */
    @Lock(LockModeType.OPTIMISTIC)
    @Query("select w from TideWindow w where w.berthType = :berthType")
    List<TideWindow> findForVerificationByBerthType(@Param("berthType") String berthType);

    /**
     * 互换确认：以 OPTIMISTIC 锁读取涉及泊位类型的全部潮汐窗口，
     * 与冻结快照逐个比对 id/内容/版本，新增窗口亦纳入潮汐约束重算。
     */
    @Lock(LockModeType.OPTIMISTIC)
    @Query("select w from TideWindow w where w.berthType in :berthTypes")
    List<TideWindow> findForVerificationByBerthTypes(@Param("berthTypes") Collection<String> berthTypes);
}
