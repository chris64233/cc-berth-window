package com.chris64233.berthwindow.repo;

import com.chris64233.berthwindow.domain.ChangeHistory;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ChangeHistoryRepository extends JpaRepository<ChangeHistory, Long> {

    List<ChangeHistory> findByApplication_IdOrderByOccurredAtAsc(Long applicationId);
}
