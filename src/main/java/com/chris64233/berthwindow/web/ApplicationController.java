package com.chris64233.berthwindow.web;

import java.util.List;

import jakarta.validation.Valid;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.chris64233.berthwindow.service.BerthWindowService;
import com.chris64233.berthwindow.web.dto.ApplicationRequest;
import com.chris64233.berthwindow.web.dto.ApplicationResponse;
import com.chris64233.berthwindow.web.dto.RescheduleRequest;

/**
 * 船舶泊位窗口申请：提交（幂等）、审批（泊位与拖轮原子占用）、改期。
 */
@RestController
@RequestMapping("/api/applications")
public class ApplicationController {

    private final BerthWindowService service;

    public ApplicationController(BerthWindowService service) {
        this.service = service;
    }

    /** 提交申请。同一 applicationNo 重复提交返回同一申请，保证业务号幂等。 */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApplicationResponse submit(@Valid @RequestBody ApplicationRequest request) {
        return DtoMapper.application(service.submit(
                request.applicationNo(), request.vesselCode(), request.vesselType(),
                request.eta(), request.etd(), request.draft(),
                request.requiredBerthType(), request.requiredTugs()));
    }

    @GetMapping
    public List<ApplicationResponse> list() {
        return service.listApplications().stream().map(DtoMapper::application).toList();
    }

    @GetMapping("/{applicationNo}")
    public ApplicationResponse get(@PathVariable String applicationNo) {
        return DtoMapper.application(service.getApplication(applicationNo));
    }

    /**
     * 审批：在单事务内同时占用连续泊位时间与靠离泊拖轮能力。
     * 任一资源不足整体拒绝（409/422），不会出现半预留状态。
     */
    @PostMapping("/{applicationNo}/approve")
    public ApplicationResponse approve(@PathVariable String applicationNo) {
        return DtoMapper.application(service.approve(applicationNo));
    }

    /** 改期：新窗口分配成功后才释放原窗口；失败时原安排保留。 */
    @PostMapping("/{applicationNo}/reschedule")
    public ApplicationResponse reschedule(@PathVariable String applicationNo,
                                          @Valid @RequestBody RescheduleRequest request) {
        return DtoMapper.application(
                service.reschedule(applicationNo, request.newEta(), request.newEtd()));
    }

    /** 取消已批准且未开始作业的安排：释放泊位与拖轮占用，并使在途互换方案失效。 */
    @PostMapping("/{applicationNo}/cancel")
    public ApplicationResponse cancel(@PathVariable String applicationNo) {
        return DtoMapper.application(service.cancel(applicationNo));
    }
}
