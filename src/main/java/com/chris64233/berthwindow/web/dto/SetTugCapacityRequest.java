package com.chris64233.berthwindow.web.dto;

import jakarta.validation.constraints.Min;

public record SetTugCapacityRequest(@Min(0) int totalTugs) {
}
