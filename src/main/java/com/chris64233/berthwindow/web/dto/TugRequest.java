package com.chris64233.berthwindow.web.dto;

import jakarta.validation.constraints.NotBlank;

public record TugRequest(
        @NotBlank String code,
        @NotBlank String name
) {
}
