package com.citygas.network.dto;

import java.math.BigDecimal;
import java.util.List;

import lombok.Getter;
import lombok.Setter;

/**
 * 编制视图：按日期查看当前片区门站日输气量（进站能力）、调压站出口能力与片区需求概况。
 *
 * <p>只读取当前片区站点（@TenantId 自动隔离）；该视图只读，不写任何数据。</p>
 */
@Getter
@Setter
public class GasPlanCapabilityVO {

    private String planDate;
    private List<Gate> gateStations;
    private List<Regulator> regulatorStations;

    @Getter
    @Setter
    public static class Gate {
        private Long id;
        private String stationCode;
        private String stationName;
        /** 门站日输气量（m³），作为当日进站/输配能力依据 */
        private BigDecimal dailyFlowM3;
        private BigDecimal inletPressureMpa;
        private String status;
    }

    @Getter
    @Setter
    public static class Regulator {
        private Long id;
        private String stationCode;
        private String stationName;
        private String stationType;
        private BigDecimal outletPressureMpa;
        /** 出口供出能力上限（m³/日），未核定为空 */
        private BigDecimal outletCapacityM3;
        private String status;
    }
}
