package com.chris64233.berthwindow.repo;

import java.util.List;

import jakarta.persistence.LockModeType;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import com.chris64233.berthwindow.domain.Tug;

public interface TugRepository extends JpaRepository<Tug, Long> {

    /**
     * 锁定全部拖轮行（拖轮池规模小），让并发的拖轮分配在数据库层串行化；
     * 唯一约束 uk_tug_time 是最终硬保护。
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from Tug t order by t.id")
    List<Tug> findAllForUpdate();
}
