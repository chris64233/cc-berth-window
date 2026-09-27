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

    List<TugAssignment> findAllByOrderByActionTimeAsc();

    void deleteByApplicationId(Long applicationId);

    /** 在给定时刻集合上已被占用的拖轮 id */
    @Query("select distinct a.tugId from TugAssignment a where a.actionTime in :times")
    List<Long> findBusyTugIds(@Param("times") Collection<Instant> times);

    /** 改期时排除本申请自身占用后的忙时拖轮 id */
    @Query("select distinct a.tugId from TugAssignment a "
            + "where a.actionTime in :times and a.applicationId <> :applicationId")
    List<Long> findBusyTugIdsExcluding(@Param("times") Collection<Instant> times,
                                       @Param("applicationId") Long applicationId);

    /** 互换时排除互换双方自身占用后的忙时拖轮 id（双方拖轮安排将被重写） */
    @Query("select distinct a.tugId from TugAssignment a "
            + "where a.actionTime in :times and a.applicationId not in :excludedApplicationIds")
    List<Long> findBusyTugIdsExcludingBoth(@Param("times") Collection<Instant> times,
                                           @Param("excludedApplicationIds") Collection<Long> excludedApplicationIds);

    /** 互换时查询排除双方后，在给定时刻集合上的全部占用明细（拖轮-时刻） */
    @Query("select a from TugAssignment a "
            + "where a.actionTime in :times and a.applicationId not in :excludedApplicationIds")
    List<TugAssignment> findAssignmentsExcludingBoth(@Param("times") Collection<Instant> times,
                                                     @Param("excludedApplicationIds") Collection<Long> excludedApplicationIds);
}
