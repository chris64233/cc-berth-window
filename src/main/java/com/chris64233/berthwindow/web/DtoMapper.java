package com.chris64233.berthwindow.web;

import java.util.List;
import java.util.Map;

import com.chris64233.berthwindow.domain.Berth;
import com.chris64233.berthwindow.domain.BerthApplication;
import com.chris64233.berthwindow.domain.BerthOccupation;
import com.chris64233.berthwindow.domain.ChangeHistory;
import com.chris64233.berthwindow.domain.TideWindow;
import com.chris64233.berthwindow.domain.Tug;
import com.chris64233.berthwindow.domain.TugAssignment;
import com.chris64233.berthwindow.web.dto.ApplicationResponse;
import com.chris64233.berthwindow.web.dto.BerthResponse;
import com.chris64233.berthwindow.web.dto.HistoryResponse;
import com.chris64233.berthwindow.web.dto.OccupationResponse;
import com.chris64233.berthwindow.web.dto.TideWindowResponse;
import com.chris64233.berthwindow.web.dto.TugAssignmentResponse;
import com.chris64233.berthwindow.web.dto.TugResponse;

/** 实体到响应 DTO 的转换。 */
public final class DtoMapper {

    private DtoMapper() {
    }

    public static BerthResponse berth(Berth b) {
        return new BerthResponse(b.getId(), b.getCode(), b.getName(), b.getBerthType(),
                b.getAcceptedVesselTypes(), b.getMaxDraft(), b.getSimultaneousCapacity(),
                b.getVersion());
    }

    public static TugResponse tug(Tug t) {
        return new TugResponse(t.getId(), t.getCode(), t.getName(), t.getVersion());
    }

    public static TideWindowResponse tide(TideWindow w) {
        return new TideWindowResponse(w.getId(), w.getBerthType(), w.getWindowStart(),
                w.getWindowEnd(), w.getAvailableDepth(), w.getVersion());
    }

    public static ApplicationResponse application(BerthApplication a) {
        return new ApplicationResponse(a.getId(), a.getApplicationNo(), a.getVesselCode(),
                a.getVesselType(), a.getEta(), a.getEtd(), a.getDraft(),
                a.getRequiredBerthType(), a.getRequiredTugs(), a.getStatus(),
                a.getAssignedBerthId(), a.getVersion());
    }

    public static OccupationResponse occupation(BerthOccupation o,
                                                Map<Long, BerthApplication> apps) {
        BerthApplication app = apps.get(o.getApplicationId());
        String appNo = app == null ? null : app.getApplicationNo();
        return new OccupationResponse(o.getId(), o.getBerthId(), o.getApplicationId(), appNo,
                o.getStartTime(), o.getEndTime());
    }

    public static TugAssignmentResponse assignment(TugAssignment a,
                                                   Map<Long, Tug> tugs,
                                                   Map<Long, BerthApplication> apps) {
        Tug tug = tugs.get(a.getTugId());
        BerthApplication app = apps.get(a.getApplicationId());
        return new TugAssignmentResponse(a.getId(), a.getTugId(),
                tug == null ? null : tug.getCode(),
                a.getApplicationId(),
                app == null ? null : app.getApplicationNo(),
                a.getActionType(), a.getActionTime());
    }

    public static HistoryResponse history(ChangeHistory h) {
        return new HistoryResponse(h.getId(), h.getApplicationId(), h.getApplicationNo(),
                h.getAction(), h.getDetail(), h.getOccurredAt());
    }

    public static List<OccupationResponse> occupations(List<BerthOccupation> occupations,
                                                       Map<Long, BerthApplication> apps) {
        return occupations.stream().map(o -> occupation(o, apps)).toList();
    }

    public static List<TugAssignmentResponse> assignments(List<TugAssignment> assignments,
                                                          Map<Long, Tug> tugs,
                                                          Map<Long, BerthApplication> apps) {
        return assignments.stream().map(a -> assignment(a, tugs, apps)).toList();
    }
}
