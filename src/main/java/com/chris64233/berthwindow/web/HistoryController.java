package com.chris64233.berthwindow.web;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.chris64233.berthwindow.service.BerthWindowService;
import com.chris64233.berthwindow.web.dto.HistoryResponse;

/** 变更历史查询，可按申请业务号过滤。 */
@RestController
public class HistoryController {

    private final BerthWindowService service;

    public HistoryController(BerthWindowService service) {
        this.service = service;
    }

    @GetMapping("/api/change-history")
    public List<HistoryResponse> history(@RequestParam(required = false) String applicationNo) {
        return service.findHistory(applicationNo).stream()
                .map(DtoMapper::history)
                .toList();
    }
}
