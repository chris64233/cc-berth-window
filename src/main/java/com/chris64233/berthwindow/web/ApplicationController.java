package com.chris64233.berthwindow.web;

import com.chris64233.berthwindow.service.BerthApplicationService;
import com.chris64233.berthwindow.web.dto.ApplicationRequest;
import com.chris64233.berthwindow.web.dto.ApplicationResponse;
import com.chris64233.berthwindow.web.dto.HistoryResponse;
import com.chris64233.berthwindow.web.dto.RescheduleRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/applications")
public class ApplicationController {

    private final BerthApplicationService applicationService;

    public ApplicationController(BerthApplicationService applicationService) {
        this.applicationService = applicationService;
    }

    /**
     * 提交申请并同步审批。响应 status 为 APPROVED 或 REJECTED；
     * 相同业务号且内容一致的重复提交返回首次审批结果（幂等）。
     */
    @PostMapping
    public ApplicationResponse apply(@Valid @RequestBody ApplicationRequest request) {
        return ApplicationResponse.from(applicationService.apply(
                request.businessNo(), request.shipName(), request.shipType(), request.draft(),
                request.expectedArrival(), request.expectedDeparture(), request.requiredTugs()));
    }

    @GetMapping("/{businessNo}")
    public ApplicationResponse get(@PathVariable String businessNo) {
        return ApplicationResponse.from(applicationService.getByBusinessNo(businessNo));
    }

    @PutMapping("/{businessNo}/reschedule")
    public ApplicationResponse reschedule(@PathVariable String businessNo,
                                          @Valid @RequestBody RescheduleRequest request) {
        return ApplicationResponse.from(applicationService.reschedule(
                businessNo, request.expectedVersion(), request.expectedArrival(), request.expectedDeparture()));
    }

    @GetMapping("/{businessNo}/history")
    public List<HistoryResponse> history(@PathVariable String businessNo) {
        return applicationService.listHistory(businessNo).stream().map(HistoryResponse::from).toList();
    }
}
