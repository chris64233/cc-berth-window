package com.chris64233.berthwindow;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.hamcrest.Matchers.notNullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class ApiWebTest {

    private static final Instant BASE = Instant.now().plus(2, ChronoUnit.DAYS).truncatedTo(ChronoUnit.HOURS);

    @Autowired
    MockMvc mockMvc;
    @Autowired
    JdbcTemplate jdbc;

    @BeforeEach
    void cleanDatabase() {
        jdbc.update("delete from change_history");
        jdbc.update("delete from berth_reservation");
        jdbc.update("delete from berth_application");
        jdbc.update("delete from tide_window");
        jdbc.update("delete from berth");
        jdbc.update("delete from port_resource");
    }

    @Test
    void fullFlowOverHttp() throws Exception {
        mockMvc.perform(post("/api/berths").contentType(MediaType.APPLICATION_JSON).content("""
                {"code":"A1","berthType":"CONTAINER","maxDraft":15.00,"concurrentCapacity":1}
                """)).andExpect(status().isCreated());

        mockMvc.perform(post("/api/berths/A1/tide-windows").contentType(MediaType.APPLICATION_JSON).content("""
                {"startTime":"%s","endTime":"%s","maxDraft":15.00}
                """.formatted(BASE, BASE.plus(2, ChronoUnit.DAYS)))).andExpect(status().isCreated());

        mockMvc.perform(put("/api/port/tugs").contentType(MediaType.APPLICATION_JSON).content("""
                {"totalTugs":4}
                """)).andExpect(status().isOk())
                .andExpect(jsonPath("$.totalTugs").value(4));

        String applyBody = """
                {"businessNo":"BN-1","shipName":"远洋一号","shipType":"CONTAINER","draft":12.50,
                 "expectedArrival":"%s","expectedDeparture":"%s","requiredTugs":2}
                """.formatted(BASE.plus(4, ChronoUnit.HOURS), BASE.plus(20, ChronoUnit.HOURS));

        mockMvc.perform(post("/api/applications").contentType(MediaType.APPLICATION_JSON).content(applyBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPROVED"))
                .andExpect(jsonPath("$.berthCode").value("A1"));

        // 幂等重放：相同业务号相同内容返回同一审批结果
        mockMvc.perform(post("/api/applications").contentType(MediaType.APPLICATION_JSON).content(applyBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPROVED"));

        // 占用查询
        mockMvc.perform(get("/api/berths/A1/reservations"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].businessNo").value("BN-1"))
                .andExpect(jsonPath("$[0].tugCount").value(2));

        // 改期
        mockMvc.perform(put("/api/applications/BN-1/reschedule").contentType(MediaType.APPLICATION_JSON).content("""
                {"expectedArrival":"%s","expectedDeparture":"%s","expectedVersion":0}
                """.formatted(BASE.plus(30, ChronoUnit.HOURS), BASE.plus(46, ChronoUnit.HOURS))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(1));

        // 变更历史
        mockMvc.perform(get("/api/applications/BN-1/history"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].changeType").value("CREATED"))
                .andExpect(jsonPath("$[1].changeType").value("RESCHEDULED"));
    }

    @Test
    void unifiedErrorResponseForUnknownApplication() throws Exception {
        mockMvc.perform(get("/api/applications/NOPE"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("APPLICATION_NOT_FOUND"))
                .andExpect(jsonPath("$.message", notNullValue()))
                .andExpect(jsonPath("$.path").value("/api/applications/NOPE"))
                .andExpect(jsonPath("$.timestamp", notNullValue()));
    }

    @Test
    void unifiedErrorResponseForValidationFailure() throws Exception {
        mockMvc.perform(post("/api/applications").contentType(MediaType.APPLICATION_JSON).content("""
                {"shipName":"x"}
                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.message", notNullValue()));
    }

    @Test
    void unifiedErrorResponseForConflictingBusinessNo() throws Exception {
        mockMvc.perform(post("/api/berths").contentType(MediaType.APPLICATION_JSON).content("""
                {"code":"A2","berthType":"CONTAINER","maxDraft":15.00,"concurrentCapacity":1}
                """)).andExpect(status().isCreated());
        mockMvc.perform(post("/api/berths/A2/tide-windows").contentType(MediaType.APPLICATION_JSON).content("""
                {"startTime":"%s","endTime":"%s","maxDraft":15.00}
                """.formatted(BASE, BASE.plus(2, ChronoUnit.DAYS)))).andExpect(status().isCreated());
        mockMvc.perform(put("/api/port/tugs").contentType(MediaType.APPLICATION_JSON).content("""
                {"totalTugs":4}
                """)).andExpect(status().isOk());

        String body = """
                {"businessNo":"BN-9","shipName":"远洋二号","shipType":"CONTAINER","draft":10.00,
                 "expectedArrival":"%s","expectedDeparture":"%s","requiredTugs":1}
                """.formatted(BASE.plus(4, ChronoUnit.HOURS), BASE.plus(20, ChronoUnit.HOURS));
        mockMvc.perform(post("/api/applications").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk());

        String conflicting = """
                {"businessNo":"BN-9","shipName":"另一条船","shipType":"CONTAINER","draft":10.00,
                 "expectedArrival":"%s","expectedDeparture":"%s","requiredTugs":1}
                """.formatted(BASE.plus(6, ChronoUnit.HOURS), BASE.plus(22, ChronoUnit.HOURS));
        mockMvc.perform(post("/api/applications").contentType(MediaType.APPLICATION_JSON).content(conflicting))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DUPLICATE_BUSINESS_NO"));
    }

    @Test
    void unifiedErrorResponseForStaleReschedule() throws Exception {
        mockMvc.perform(post("/api/berths").contentType(MediaType.APPLICATION_JSON).content("""
                {"code":"A3","berthType":"CONTAINER","maxDraft":15.00,"concurrentCapacity":1}
                """)).andExpect(status().isCreated());
        mockMvc.perform(post("/api/berths/A3/tide-windows").contentType(MediaType.APPLICATION_JSON).content("""
                {"startTime":"%s","endTime":"%s","maxDraft":15.00}
                """.formatted(BASE, BASE.plus(3, ChronoUnit.DAYS)))).andExpect(status().isCreated());
        mockMvc.perform(put("/api/port/tugs").contentType(MediaType.APPLICATION_JSON).content("""
                {"totalTugs":4}
                """)).andExpect(status().isOk());
        mockMvc.perform(post("/api/applications").contentType(MediaType.APPLICATION_JSON).content("""
                {"businessNo":"BN-10","shipName":"远洋三号","shipType":"CONTAINER","draft":10.00,
                 "expectedArrival":"%s","expectedDeparture":"%s","requiredTugs":1}
                """.formatted(BASE.plus(4, ChronoUnit.HOURS), BASE.plus(20, ChronoUnit.HOURS))))
                .andExpect(status().isOk());

        mockMvc.perform(put("/api/applications/BN-10/reschedule").contentType(MediaType.APPLICATION_JSON).content("""
                {"expectedArrival":"%s","expectedDeparture":"%s","expectedVersion":7}
                """.formatted(BASE.plus(30, ChronoUnit.HOURS), BASE.plus(46, ChronoUnit.HOURS))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("STALE_APPLICATION"));
    }
}
