package com.chris64233.berthwindow.repo;

import java.time.Instant;
import java.util.Collection;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.chris64233.berthwindow.domain.BerthOccupation;

public interface BerthOccupationRepository extends JpaRepository<BerthOccupation, Long> {

    List<BerthOccupation> findByBerthIdOrderByStartTimeAsc(Long berthId);

    List<BerthOccupation> findByApplicationId(Long applicationId);

    List<BerthOccupation> findAllByOrderByStartTimeAsc();

    void deleteByApplicationId(Long applicationId);

    /**
     * 统计与 [start, end) 重叠的占用数量（半开区间，端点相接不算重叠）。
     * 必须在持有该泊位行的数据库写锁时调用。
     */
    @Query("select count(o) from BerthOccupation o where o.berthId = :berthId "
            + "and o.startTime < :end and o.endTime > :start")
    long countOverlapping(@Param("berthId") Long berthId,
                          @Param("start") Instant start,
                          @Param("end") Instant end);

    /** 改期时排除自身占用后的重叠数量 */
    @Query("select count(o) from BerthOccupation o where o.berthId = :berthId "
            + "and o.applicationId <> :applicationId "
            + "and o.startTime < :end and o.endTime > :start")
    long countOverlappingExcluding(@Param("berthId") Long berthId,
                                   @Param("applicationId") Long applicationId,
                                   @Param("start") Instant start,
                                   @Param("end") Instant end);

    /** 互换时排除互换双方自身占用后的重叠数量（双方占用将被重写） */
    @Query("select count(o) from BerthOccupation o where o.berthId = :berthId "
            + "and o.applicationId not in :excludedApplicationIds "
            + "and o.startTime < :end and o.endTime > :start")
    long countOverlappingExcludingBoth(@Param("berthId") Long berthId,
                                       @Param("excludedApplicationIds") Collection<Long> excludedApplicationIds,
                                       @Param("start") Instant start,
                                       @Param("end") Instant end);
}
