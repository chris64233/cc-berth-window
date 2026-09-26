package com.chris64233.berthwindow.repo;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.chris64233.berthwindow.domain.ChangeHistory;

public interface ChangeHistoryRepository extends JpaRepository<ChangeHistory, Long> {

    List<ChangeHistory> findByApplicationIdOrderByOccurredAtAscIdAsc(Long applicationId);

    List<ChangeHistory> findByApplicationNoOrderByOccurredAtAscIdAsc(String applicationNo);

    List<ChangeHistory> findAllByOrderByOccurredAtAscIdAsc();
}
