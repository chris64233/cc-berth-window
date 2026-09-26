package com.chris64233.berthwindow.repo;

import com.chris64233.berthwindow.domain.PortResource;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import java.util.Optional;

public interface PortResourceRepository extends JpaRepository<PortResource, Long> {

    /**
     * 审批/改期时对港口资源行加悲观写锁，串行化全港拖轮余量决策。
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from PortResource p where p.id = " + PortResource.SINGLETON_ID)
    Optional<PortResource> lockSingleton();
}
