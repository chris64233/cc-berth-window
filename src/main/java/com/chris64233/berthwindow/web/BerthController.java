package com.chris64233.berthwindow.web;

import com.chris64233.berthwindow.service.BerthAdminService;
import com.chris64233.berthwindow.web.dto.BerthResponse;
import com.chris64233.berthwindow.web.dto.CreateBerthRequest;
import com.chris64233.berthwindow.web.dto.ReservationResponse;
import com.chris64233.berthwindow.web.dto.TideWindowRequest;
import com.chris64233.berthwindow.web.dto.TideWindowResponse;
import jakarta.validation.Valid;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;

@RestController
@RequestMapping("/api/berths")
public class BerthController {

    private static final Instant DEFAULT_FROM = Instant.parse("1970-01-01T00:00:00Z");
    private static final Instant DEFAULT_TO = Instant.parse("2100-01-01T00:00:00Z");

    private final BerthAdminService adminService;

    public BerthController(BerthAdminService adminService) {
        this.adminService = adminService;
    }

    @PostMapping
    public ResponseEntity<BerthResponse> create(@Valid @RequestBody CreateBerthRequest request) {
        var berth = adminService.createBerth(request.code(), request.berthType(),
                request.maxDraft(), request.concurrentCapacity());
        return ResponseEntity.status(HttpStatus.CREATED).body(BerthResponse.from(berth));
    }

    @GetMapping("/{code}")
    public BerthResponse get(@PathVariable String code) {
        return BerthResponse.from(adminService.getBerth(code));
    }

    @PostMapping("/{code}/tide-windows")
    public ResponseEntity<TideWindowResponse> addTideWindow(@PathVariable String code,
                                                            @Valid @RequestBody TideWindowRequest request) {
        var window = adminService.addTideWindow(code, request.startTime(), request.endTime(), request.maxDraft());
        return ResponseEntity.status(HttpStatus.CREATED).body(TideWindowResponse.from(window));
    }

    @PutMapping("/{code}/tide-windows/{id}")
    public TideWindowResponse updateTideWindow(@PathVariable String code, @PathVariable Long id,
                                               @Valid @RequestBody TideWindowRequest request) {
        return TideWindowResponse.from(
                adminService.updateTideWindow(code, id, request.startTime(), request.endTime(), request.maxDraft()));
    }

    @GetMapping("/{code}/tide-windows")
    public List<TideWindowResponse> listTideWindows(@PathVariable String code) {
        return adminService.listTideWindows(code).stream().map(TideWindowResponse::from).toList();
    }

    /**
     * 泊位资源占用查询：返回与 [from, to) 有交集的占用记录。
     */
    @GetMapping("/{code}/reservations")
    public List<ReservationResponse> occupancy(
            @PathVariable String code,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to) {
        return adminService.listOccupancy(code, from == null ? DEFAULT_FROM : from, to == null ? DEFAULT_TO : to)
                .stream().map(ReservationResponse::from).toList();
    }
}
