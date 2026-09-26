package com.chris64233.berthwindow.web.dto;

import com.chris64233.berthwindow.domain.PortResource;

public record TugCapacityResponse(int totalTugs) {

    public static TugCapacityResponse from(PortResource resource) {
        return new TugCapacityResponse(resource.getTotalTugs());
    }
}
