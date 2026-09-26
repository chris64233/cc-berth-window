package com.chris64233.berthwindow.web;

import java.util.List;
import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.chris64233.berthwindow.domain.BerthApplication;
import com.chris64233.berthwindow.domain.BerthOccupation;
import com.chris64233.berthwindow.domain.Tug;
import com.chris64233.berthwindow.domain.TugAssignment;
import com.chris64233.berthwindow.service.BerthWindowService;
import com.chris64233.berthwindow.web.dto.OccupationResponse;
import com.chris64233.berthwindow.web.dto.TugAssignmentResponse;

/**
 * 资源占用查询：泊位占用与拖轮安排，可按申请业务号过滤。
 */
@RestController
public class OccupationQueryController {

    private final BerthWindowService service;

    public OccupationQueryController(BerthWindowService service) {
        this.service = service;
    }

    @GetMapping("/api/occupations")
    public List<OccupationResponse> occupations(@RequestParam(required = false) Long berthId,
                                                @RequestParam(required = false) String applicationNo) {
        List<BerthOccupation> occupations = service.findOccupations(berthId, applicationNo);
        Map<Long, BerthApplication> apps = service.applicationMap(
                occupations.stream().map(BerthOccupation::getApplicationId).distinct().toList());
        return DtoMapper.occupations(occupations, apps);
    }

    @GetMapping("/api/tug-assignments")
    public List<TugAssignmentResponse> assignments(@RequestParam(required = false) String applicationNo) {
        List<TugAssignment> assignments = service.findAssignments(applicationNo);
        Map<Long, BerthApplication> apps = service.applicationMap(
                assignments.stream().map(TugAssignment::getApplicationId).distinct().toList());
        Map<Long, Tug> tugs = service.tugMap(
                assignments.stream().map(TugAssignment::getTugId).distinct().toList());
        return DtoMapper.assignments(assignments, tugs, apps);
    }
}
