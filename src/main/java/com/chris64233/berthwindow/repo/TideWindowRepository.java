package com.chris64233.berthwindow.repo;

import java.util.List;

import jakarta.persistence.LockModeType;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.chris64233.berthwindow.domain.TideWindow;

public interface TideWindowRepository extends JpaRepository<TideWindow, Long> {

    List<TideWindow> findByBerthType(String berthType);

    /**
     * 以 OPTIMISTIC（version 校验）方式读取潮汐窗口：提交时 Hibernate 会再次校验
     * 返回窗口的版本号，若审批期间潮汐数据被其它事务修改，本次审批失败回滚，
     * 不允许使用过期的潮汐判断结果。
     */
    @Lock(LockModeType.OPTIMISTIC)
    @Query("select w from TideWindow w where w.berthType = :berthType")
    List<TideWindow> findForVerificationByBerthType(@Param("berthType") String berthType);
}
