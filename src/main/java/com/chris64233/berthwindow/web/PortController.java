package com.chris64233.berthwindow.web;

import com.chris64233.berthwindow.service.BerthAdminService;
import com.chris64233.berthwindow.web.dto.SetTugCapacityRequest;
import com.chris64233.berthwindow.web.dto.TugCapacityResponse;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/port")
public class PortController {

    private final BerthAdminService adminService;

    public PortController(BerthAdminService adminService) {
        this.adminService = adminService;
    }

    @PutMapping("/tugs")
    public TugCapacityResponse setTugCapacity(@Valid @RequestBody SetTugCapacityRequest request) {
        return TugCapacityResponse.from(adminService.setTotalTugs(request.totalTugs()));
    }

    @GetMapping("/tugs")
    public TugCapacityResponse getTugCapacity() {
        return TugCapacityResponse.from(adminService.getPortResource());
    }
}
