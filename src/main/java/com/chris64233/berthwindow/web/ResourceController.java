package com.chris64233.berthwindow.web;

import java.util.List;

import jakarta.validation.Valid;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.chris64233.berthwindow.service.BerthWindowService;
import com.chris64233.berthwindow.web.dto.BerthRequest;
import com.chris64233.berthwindow.web.dto.BerthResponse;
import com.chris64233.berthwindow.web.dto.TideDepthUpdateRequest;
import com.chris64233.berthwindow.web.dto.TideWindowRequest;
import com.chris64233.berthwindow.web.dto.TideWindowResponse;
import com.chris64233.berthwindow.web.dto.TugRequest;
import com.chris64233.berthwindow.web.dto.TugResponse;

/** 泊位、拖轮、潮汐等基础资源维护接口。 */
@RestController
@RequestMapping("/api/resources")
public class ResourceController {

    private final BerthWindowService service;

    public ResourceController(BerthWindowService service) {
        this.service = service;
    }

    @PostMapping("/berths")
    @ResponseStatus(HttpStatus.CREATED)
    public BerthResponse createBerth(@Valid @RequestBody BerthRequest request) {
        return DtoMapper.berth(service.createBerth(request.code(), request.name(),
                request.berthType(), request.acceptedVesselTypes(), request.maxDraft(),
                request.simultaneousCapacity()));
    }

    @GetMapping("/berths")
    public List<BerthResponse> listBerths() {
        return service.listBerths().stream().map(DtoMapper::berth).toList();
    }

    @GetMapping("/berths/{id}")
    public BerthResponse getBerth(@PathVariable Long id) {
        return DtoMapper.berth(service.getBerth(id));
    }

    @PostMapping("/tugs")
    @ResponseStatus(HttpStatus.CREATED)
    public TugResponse createTug(@Valid @RequestBody TugRequest request) {
        return DtoMapper.tug(service.createTug(request.code(), request.name()));
    }

    @GetMapping("/tugs")
    public List<TugResponse> listTugs() {
        return service.listTugs().stream().map(DtoMapper::tug).toList();
    }

    @PostMapping("/tide-windows")
    @ResponseStatus(HttpStatus.CREATED)
    public TideWindowResponse createTideWindow(@Valid @RequestBody TideWindowRequest request) {
        return DtoMapper.tide(service.createTideWindow(request.berthType(),
                request.windowStart(), request.windowEnd(), request.availableDepth()));
    }

    @GetMapping("/tide-windows")
    public List<TideWindowResponse> listTideWindows(@RequestParam(required = false) String berthType) {
        return service.listTideWindows(berthType).stream().map(DtoMapper::tide).toList();
    }

    /** 修改潮汐窗口水深（模拟潮汐预报数据更新），可携带 version 做并发控制 */
    @PatchMapping("/tide-windows/{id}")
    public TideWindowResponse updateTideDepth(@PathVariable Long id,
                                              @Valid @RequestBody TideDepthUpdateRequest request) {
        return DtoMapper.tide(service.updateTideDepth(id, request.availableDepth(),
                request.expectedVersion()));
    }
}
