package com.chris64233.berthwindow.repo;

import java.time.Instant;
import java.util.Collection;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.chris64233.berthwindow.domain.TugAssignment;

public interface TugAssignmentRepository extends JpaRepository<TugAssignment, Long> {

    List<TugAssignment> findByApplicationId(Long applicationId);

    List<TugAssignment> findByApplicationIdIn(Collection<Long> applicationIds);

    List<TugAssignment> findAllByOrderByActionTimeAsc();

    void deleteByApplicationId(Long applicationId);

    /** 互换确认：一次性删除双方原拖轮安排 */
    void deleteByApplicationIdIn(Collection<Long> applicationIds);

    /** 在给定时刻集合上已被占用的拖轮 id */
    @Query("select distinct a.tugId from TugAssignment a where a.actionTime in :times")
    List<Long> findBusyTugIds(@Param("times") Collection<Instant> times);

    /** 改期时排除本申请自身占用后的忙时拖轮 id */
    @Query("select distinct a.tugId from TugAssignment a "
            + "where a.actionTime in :times and a.applicationId <> :applicationId")
    List<Long> findBusyTugIdsExcluding(@Param("times") Collection<Instant> times,
                                       @Param("applicationId") Long applicationId);

    /** 互换确认：排除互换双方原占用后的忙时拖轮 id（双方原安排将在同一事务内切换） */
    @Query("select distinct a.tugId from TugAssignment a "
            + "where a.actionTime in :times and a.applicationId not in :excludedApplicationIds")
    List<Long> findBusyTugIdsExcludingApplications(
            @Param("times") Collection<Instant> times,
            @Param("excludedApplicationIds") Collection<Long> excludedApplicationIds);
}
