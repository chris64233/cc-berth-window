package com.chris64233.berthwindow.repo;

import com.chris64233.berthwindow.domain.BerthReservation;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface BerthReservationRepository extends JpaRepository<BerthReservation, Long> {

    Optional<BerthReservation> findByApplication_Id(Long applicationId);

    /**
     * 泊位占用查询：与 [from, to) 有交集的占用记录。
     */
    @Query("select r from BerthReservation r where r.berth.code = :berthCode "
            + "and r.startTime < :to and r.endTime > :from order by r.startTime")
    List<BerthReservation> findOccupancy(@Param("berthCode") String berthCode,
                                         @Param("from") Instant from,
                                         @Param("to") Instant to);
}
