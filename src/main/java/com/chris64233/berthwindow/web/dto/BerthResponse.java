package com.chris64233.berthwindow.web.dto;

import com.chris64233.berthwindow.domain.Berth;

import java.math.BigDecimal;

public record BerthResponse(String code, String berthType, BigDecimal maxDraft, int concurrentCapacity) {

    public static BerthResponse from(Berth berth) {
        return new BerthResponse(berth.getCode(), berth.getBerthType(),
                berth.getMaxDraft(), berth.getConcurrentCapacity());
    }
}
