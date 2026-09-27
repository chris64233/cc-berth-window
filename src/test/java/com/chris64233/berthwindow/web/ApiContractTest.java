package com.chris64233.berthwindow.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import com.chris64233.berthwindow.AbstractIntegrationTest;
import com.chris64233.berthwindow.service.BerthWindowService;

@AutoConfigureMockMvc
class ApiContractTest extends AbstractIntegrationTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private BerthWindowService service;

    @BeforeEach
    void setUp() {
        service.createBerth("WB", "WB 泊位", "CONTAINER",
                java.util.Set.of("FEEDER"), new BigDecimal("13.00"), 1);
        service.createBerth("WB2B", "WB2B 泊位", "CONTAINER",
                java.util.Set.of("FEEDER"), new BigDecimal("13.00"), 1);
        service.createTug("WT", "WT 拖轮");
        service.createTug("WT2", "WT2 拖轮");
        service.createTideWindow("CONTAINER",
                java.time.Instant.parse("2026-10-01T09:00:00Z"),
                java.time.Instant.parse("2026-10-01T21:00:00Z"),
                new BigDecimal("12.00"));
    }

    @Test
    void happyPath_submitAndApprove() throws Exception {
        String body = """
                {
                  "applicationNo": "WEB-1",
                  "vesselCode": "VESSEL-1",
                  "vesselType": "FEEDER",
                  "eta": "2026-10-01T10:00:00Z",
                  "etd": "2026-10-01T20:00:00Z",
                  "draft": 10.00,
                  "requiredBerthType": "CONTAINER",
                  "requiredTugs": 1
                }
                """;
        mockMvc.perform(post("/api/applications").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.applicationNo").value("WEB-1"));

        mockMvc.perform(post("/api/applications/WEB-1/approve"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPROVED"))
                .andExpect(jsonPath("$.assignedBerthId").exists());

        mockMvc.perform(get("/api/occupations").param("applicationNo", "WEB-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].applicationNo").value("WEB-1"));

        mockMvc.perform(get("/api/tug-assignments").param("applicationNo", "WEB-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2));

        mockMvc.perform(get("/api/change-history").param("applicationNo", "WEB-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[1].action").value("APPROVED"));
    }

    @Test
    void duplicateSubmit_isIdempotent() throws Exception {
        String body = """
                {
                  "applicationNo": "WEB-2",
                  "vesselCode": "V2",
                  "vesselType": "FEEDER",
                  "eta": "2026-10-01T10:00:00Z",
                  "etd": "2026-10-01T20:00:00Z",
                  "draft": 10.00,
                  "requiredBerthType": "CONTAINER",
                  "requiredTugs": 0
                }
                """;
        mockMvc.perform(post("/api/applications").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/applications").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.applicationNo").value("WEB-2"));
    }

    @Test
    void validationError_returnsUnifiedShape() throws Exception {
        String body = """
                {
                  "applicationNo": "",
                  "vesselCode": "V3",
                  "vesselType": "FEEDER",
                  "eta": "2026-10-01T20:00:00Z",
                  "etd": "2026-10-01T10:00:00Z",
                  "draft": -1,
                  "requiredBerthType": "CONTAINER",
                  "requiredTugs": 0
                }
                """;
        mockMvc.perform(post("/api/applications").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.timestamp").exists())
                .andExpect(jsonPath("$.details").isArray());
    }

    @Test
    void notFound_returnsUnifiedShape() throws Exception {
        mockMvc.perform(get("/api/applications/NO-SUCH"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("APPLICATION_NOT_FOUND"))
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.message").exists());
    }

    @Test
    void approveMissingTides_returns422UnifiedError() throws Exception {
        service.createBerth("WB2", "WB2 泊位", "BULK",
                java.util.Set.of("FEEDER"), new BigDecimal("13.00"), 1);
        String body = """
                {
                  "applicationNo": "WEB-4",
                  "vesselCode": "V4",
                  "vesselType": "FEEDER",
                  "eta": "2026-10-01T10:00:00Z",
                  "etd": "2026-10-01T20:00:00Z",
                  "draft": 10.00,
                  "requiredBerthType": "BULK",
                  "requiredTugs": 0
                }
                """;
        mockMvc.perform(post("/api/applications").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/applications/WEB-4/approve"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("TIDE_WINDOW_UNAVAILABLE"))
                .andExpect(jsonPath("$.status").value(422));
    }

    @Test
    void approveWithoutEnoughTugs_returns409AndNoOccupation() throws Exception {
        String body = """
                {
                  "applicationNo": "WEB-5",
                  "vesselCode": "V5",
                  "vesselType": "FEEDER",
                  "eta": "2026-10-01T10:00:00Z",
                  "etd": "2026-10-01T20:00:00Z",
                  "draft": 10.00,
                  "requiredBerthType": "CONTAINER",
                  "requiredTugs": 5
                }
                """;
        mockMvc.perform(post("/api/applications").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/applications/WEB-5/approve"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INSUFFICIENT_TUGS"));

        mockMvc.perform(get("/api/occupations"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void rescheduleFailure_keepsOriginalWindow() throws Exception {
        String body = """
                {
                  "applicationNo": "WEB-6",
                  "vesselCode": "V6",
                  "vesselType": "FEEDER",
                  "eta": "2026-10-01T10:00:00Z",
                  "etd": "2026-10-01T20:00:00Z",
                  "draft": 10.00,
                  "requiredBerthType": "CONTAINER",
                  "requiredTugs": 0
                }
                """;
        mockMvc.perform(post("/api/applications").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/applications/WEB-6/approve"))
                .andExpect(status().isOk());

        // 改到无潮汐窗口的时段 -> 422，原窗口保留
        String reschedule = """
                {
                  "newEta": "2026-10-02T10:00:00Z",
                  "newEtd": "2026-10-02T20:00:00Z"
                }
                """;
        mockMvc.perform(post("/api/applications/WEB-6/reschedule")
                        .contentType(MediaType.APPLICATION_JSON).content(reschedule))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("TIDE_WINDOW_UNAVAILABLE"));

        mockMvc.perform(get("/api/applications/WEB-6"))
                .andExpect(jsonPath("$.eta").value("2026-10-01T10:00:00Z"))
                .andExpect(jsonPath("$.status").value("APPROVED"));
        mockMvc.perform(get("/api/occupations").param("applicationNo", "WEB-6"))
                .andExpect(jsonPath("$[0].startTime").value("2026-10-01T10:00:00Z"));
    }

    @Test
    void swapProposeAndConfirm_swapsBerthWindows() throws Exception {
        submitAndApprove("WEB-SA", "2026-10-01T10:00:00Z", "2026-10-01T12:00:00Z", 1);
        submitAndApprove("WEB-SB", "2026-10-01T12:00:00Z", "2026-10-01T20:00:00Z", 1);

        String propose = """
                {
                  "swapNo": "WEB-SWAP-1",
                  "applicationANo": "WEB-SA",
                  "applicationBNo": "WEB-SB"
                }
                """;
        mockMvc.perform(post("/api/swaps").contentType(MediaType.APPLICATION_JSON).content(propose))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.swapNo").value("WEB-SWAP-1"))
                .andExpect(jsonPath("$.status").value("PROPOSED"));

        // 幂等：同号同双方重复提议返回同一方案
        mockMvc.perform(post("/api/swaps").contentType(MediaType.APPLICATION_JSON).content(propose))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("PROPOSED"));

        mockMvc.perform(post("/api/swaps/WEB-SWAP-1/confirm"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CONFIRMED"))
                .andExpect(jsonPath("$.failureReason").doesNotExist());

        // 双方时间已互换
        mockMvc.perform(get("/api/applications/WEB-SA"))
                .andExpect(jsonPath("$.eta").value("2026-10-01T12:00:00Z"))
                .andExpect(jsonPath("$.etd").value("2026-10-01T20:00:00Z"));
        mockMvc.perform(get("/api/applications/WEB-SB"))
                .andExpect(jsonPath("$.eta").value("2026-10-01T10:00:00Z"))
                .andExpect(jsonPath("$.etd").value("2026-10-01T12:00:00Z"));

        // 互换前后安排均有历史
        mockMvc.perform(get("/api/change-history").param("applicationNo", "WEB-SA"))
                .andExpect(jsonPath("$[*].action").value(org.hamcrest.Matchers.containsInAnyOrder(
                        "SUBMITTED", "APPROVED", "SWAP_PROPOSED", "SWAP_CONFIRMED")));

        // 重复确认幂等
        mockMvc.perform(post("/api/swaps/WEB-SWAP-1/confirm"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CONFIRMED"));
    }

    @Test
    void swapConfirmFailure_marksFailedAndKeepsOriginalArrangements() throws Exception {
        submitAndApprove("WEB-FA", "2026-10-01T10:00:00Z", "2026-10-01T12:00:00Z", 1);
        submitAndApprove("WEB-FB", "2026-10-01T12:00:00Z", "2026-10-01T20:00:00Z", 1);
        String propose = """
                {
                  "swapNo": "WEB-SWAP-F",
                  "applicationANo": "WEB-FA",
                  "applicationBNo": "WEB-FB"
                }
                """;
        mockMvc.perform(post("/api/swaps").contentType(MediaType.APPLICATION_JSON).content(propose))
                .andExpect(status().isCreated());

        // 冻结后改期 WEB-FA -> 方案版本失效
        mockMvc.perform(post("/api/applications/WEB-FA/reschedule")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"newEta":"2026-10-01T10:30:00Z","newEtd":"2026-10-01T11:30:00Z"}
                                """))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/swaps/WEB-SWAP-F/confirm"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SWAP_STALE"))
                .andExpect(jsonPath("$.message").exists());

        mockMvc.perform(get("/api/swaps/WEB-SWAP-F"))
                .andExpect(jsonPath("$.status").value("FAILED"))
                .andExpect(jsonPath("$.failureReason").exists());
        // WEB-FB 原安排保留
        mockMvc.perform(get("/api/applications/WEB-FB"))
                .andExpect(jsonPath("$.eta").value("2026-10-01T12:00:00Z"))
                .andExpect(jsonPath("$.status").value("APPROVED"));
        mockMvc.perform(get("/api/change-history").param("applicationNo", "WEB-FB"))
                .andExpect(jsonPath("$[*].action").value(
                        org.hamcrest.Matchers.hasItem("SWAP_FAILED")));
    }

    @Test
    void cancelApprovedApplication_releasesWindow() throws Exception {
        submitAndApprove("WEB-CA", "2026-10-01T10:00:00Z", "2026-10-01T20:00:00Z", 0);

        mockMvc.perform(post("/api/applications/WEB-CA/cancel"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));

        mockMvc.perform(get("/api/occupations").param("applicationNo", "WEB-CA"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    private void submitAndApprove(String no, String eta, String etd, int requiredTugs) throws Exception {
        String body = """
                {
                  "applicationNo": "%s",
                  "vesselCode": "V-%s",
                  "vesselType": "FEEDER",
                  "eta": "%s",
                  "etd": "%s",
                  "draft": 10.00,
                  "requiredBerthType": "CONTAINER",
                  "requiredTugs": %d
                }
                """.formatted(no, no, eta, etd, requiredTugs);
        mockMvc.perform(post("/api/applications").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/applications/" + no + "/approve"))
                .andExpect(status().isOk());
    }
}
