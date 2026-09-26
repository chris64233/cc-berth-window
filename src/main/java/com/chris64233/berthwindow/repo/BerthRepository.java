package com.chris64233.berthwindow.repo;

import java.util.List;
import java.util.Optional;

import jakarta.persistence.LockModeType;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.chris64233.berthwindow.domain.Berth;

public interface BerthRepository extends JpaRepository<Berth, Long> {

    List<Berth> findByBerthTypeOrderById(String berthType);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select b from Berth b where b.id = :id")
    Optional<Berth> findWithLockById(@Param("id") Long id);
}
