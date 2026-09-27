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
        service.createTug("WT", "WT 拖轮");
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

    private void submitAndApprove(String no, String eta, String etd, int tugs) throws Exception {
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
                """.formatted(no, no, eta, etd, tugs);
        mockMvc.perform(post("/api/applications").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/applications/" + no + "/approve"))
                .andExpect(status().isOk());
    }

    @Test
    void swapFreezeAndConfirm_happyPath() throws Exception {
        // 第二个泊位
        mockMvc.perform(post("/api/resources/berths").contentType(MediaType.APPLICATION_JSON).content("""
                {"code":"WB2","name":"WB2 泊位","berthType":"CONTAINER",
                 "acceptedVesselTypes":["FEEDER"],"maxDraft":13.00,"simultaneousCapacity":1}"""))
                .andExpect(status().isCreated());

        submitAndApprove("WEB-S1", "2026-10-01T10:00:00Z", "2026-10-01T12:00:00Z", 0);
        submitAndApprove("WEB-S2", "2026-10-01T14:00:00Z", "2026-10-01T16:00:00Z", 0);

        String proposal = """
                {"proposalNo":"SWP-1","applicationANo":"WEB-S1","applicationBNo":"WEB-S2"}""";
        mockMvc.perform(post("/api/swaps").contentType(MediaType.APPLICATION_JSON).content(proposal))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("PROPOSED"))
                .andExpect(jsonPath("$.applicationANo").value("WEB-S1"))
                .andExpect(jsonPath("$.aEta").value("2026-10-01T10:00:00Z"))
                .andExpect(jsonPath("$.bEta").value("2026-10-01T14:00:00Z"));

        mockMvc.perform(post("/api/swaps/SWP-1/confirm"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.applicationA.eta").value("2026-10-01T14:00:00Z"))
                .andExpect(jsonPath("$.applicationB.eta").value("2026-10-01T10:00:00Z"));

        // 重复确认幂等：不会交换第二次
        mockMvc.perform(post("/api/swaps/SWP-1/confirm"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.applicationA.eta").value("2026-10-01T14:00:00Z"));

        mockMvc.perform(get("/api/change-history").param("applicationNo", "WEB-S1"))
                .andExpect(jsonPath("$[*].action").value(
                        org.hamcrest.Matchers.hasItem("SWAPPED")));
    }

    @Test
    void swapConfirmFailure_returns422AndKeepsOriginalArrangements() throws Exception {
        mockMvc.perform(post("/api/resources/berths").contentType(MediaType.APPLICATION_JSON).content("""
                {"code":"WB2","name":"WB2 泊位","berthType":"CONTAINER",
                 "acceptedVesselTypes":["FEEDER"],"maxDraft":13.00,"simultaneousCapacity":1}"""))
                .andExpect(status().isCreated());
        // 10-02 只有水深 9m 的窗口：小吃水船可安排，大吃水船交换过来则潮汐不满足
        mockMvc.perform(post("/api/resources/tide-windows")
                        .contentType(MediaType.APPLICATION_JSON).content("""
                {"berthType":"CONTAINER","windowStart":"2026-10-02T09:00:00Z",
                 "windowEnd":"2026-10-02T21:00:00Z","availableDepth":9.00}"""))
                .andExpect(status().isCreated());

        // S3 吃水 10m（setUp 的 10-01 窗口水深 12m 满足）
        submitAndApprove("WEB-S3", "2026-10-01T10:00:00Z", "2026-10-01T11:00:00Z", 0);
        // S4 吃水 8m，在 10-02 的 9m 窗口下可批准
        String s4 = """
                {
                  "applicationNo": "WEB-S4",
                  "vesselCode": "V-WEB-S4",
                  "vesselType": "FEEDER",
                  "eta": "2026-10-02T10:00:00Z",
                  "etd": "2026-10-02T12:00:00Z",
                  "draft": 8.00,
                  "requiredBerthType": "CONTAINER",
                  "requiredTugs": 0
                }
                """;
        mockMvc.perform(post("/api/applications").contentType(MediaType.APPLICATION_JSON).content(s4))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/applications/WEB-S4/approve")).andExpect(status().isOk());

        mockMvc.perform(post("/api/swaps").contentType(MediaType.APPLICATION_JSON).content("""
                {"proposalNo":"SWP-2","applicationANo":"WEB-S3","applicationBNo":"WEB-S4"}"""))
                .andExpect(status().isCreated());

        // 确认：S3 交换到 10-02 后吃水 10m > 水深 9m -> 422
        mockMvc.perform(post("/api/swaps/SWP-2/confirm"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("TIDE_WINDOW_UNAVAILABLE"))
                .andExpect(jsonPath("$.status").value(422));

        // 方案已落库 FAILED，双方原安排保持可用
        mockMvc.perform(get("/api/swaps/SWP-2"))
                .andExpect(jsonPath("$.status").value("FAILED"))
                .andExpect(jsonPath("$.failureCode").value("TIDE_WINDOW_UNAVAILABLE"))
                .andExpect(jsonPath("$.failureReason").exists());
        mockMvc.perform(get("/api/applications/WEB-S3"))
                .andExpect(jsonPath("$.eta").value("2026-10-01T10:00:00Z"))
                .andExpect(jsonPath("$.status").value("APPROVED"));
        mockMvc.perform(get("/api/applications/WEB-S4"))
                .andExpect(jsonPath("$.eta").value("2026-10-02T10:00:00Z"))
                .andExpect(jsonPath("$.status").value("APPROVED"));

        // 重复确认：返回同一失败结论（幂等）
        mockMvc.perform(post("/api/swaps/SWP-2/confirm"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("TIDE_WINDOW_UNAVAILABLE"));
    }

    @Test
    void swapFreeze_sameApplication_returns400() throws Exception {
        submitAndApprove("WEB-S5", "2026-10-01T10:00:00Z", "2026-10-01T12:00:00Z", 0);
        mockMvc.perform(post("/api/swaps").contentType(MediaType.APPLICATION_JSON).content("""
                {"proposalNo":"SWP-3","applicationANo":"WEB-S5","applicationBNo":"WEB-S5"}"""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("SWAP_SAME_APPLICATION"));
    }
}
