package com.chris64233.berthwindow.repo;

import com.chris64233.berthwindow.domain.BerthApplication;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface BerthApplicationRepository extends JpaRepository<BerthApplication, Long> {

    Optional<BerthApplication> findByBusinessNo(String businessNo);
}
