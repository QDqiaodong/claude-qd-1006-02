package com.waterworks.plant.common;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import com.waterworks.plant.type.WaterSample;

/**
 * 自来水厂词典与业务规则参数表：原水水质状态、沉淀池 / 滤池状态流转、滤池类型、
 * 加药药剂，以及 pH / 浊度 / 余氯等各类阈值。
 *
 * <p>规则集中在此，便于出题时被要求调整或扩展。</p>
 */
public final class Dict {

    private Dict() {
    }

    // ---------------- 原水水质状态 ----------------
    public static final List<String> RAW_WATER_STATUSES = List.of("合格", "预警", "超标");

    // ---------------- 沉淀池状态：运行 ↔ 清洗 ----------------
    public static final List<String> TANK_STATUSES = List.of("运行", "清洗");

    private static final Map<String, List<String>> TANK_NEXT = Map.of(
            "运行", List.of("清洗"),
            "清洗", List.of("运行"));

    // ---------------- 滤池类型与状态 ----------------
    public static final List<String> FILTER_TYPES = List.of("砂滤", "炭滤");

    public static final List<String> FILTER_STATUSES = List.of("运行", "反洗");

    private static final Map<String, List<String>> FILTER_NEXT = Map.of(
            "运行", List.of("反洗"),
            "反洗", List.of("运行"));

    // ---------------- 加药药剂 ----------------
    public static final List<String> CHEM_NAMES = List.of("聚合氯化铝", "次氯酸钠");

    // ---------------- 系统角色 ----------------
    public static final String ROLE_OPERATOR = "运行工";
    public static final String ROLE_LAB = "化验员";
    public static final String ROLE_ADMIN = "管理员";

    // ---------------- 工艺配置项类型 ----------------
    public static final String CONFIG_CHEM = "药剂";
    public static final String CONFIG_TARGET = "目标工段";
    public static final List<String> CONFIG_TYPES = List.of(CONFIG_CHEM, CONFIG_TARGET);

    // ---------------- 加药批次状态机 ----------------
    // 待确认 → 已确认；待确认 → 异常待复核 → 已确认 / 已退回。
    // 已确认 / 已退回 为终态；已退回保留原始登记，不得覆盖成另一条正常记录。
    public static final String DOSING_PENDING = "待确认";
    public static final String DOSING_CONFIRMED = "已确认";
    public static final String DOSING_ANOMALY = "异常待复核";
    public static final String DOSING_RETURNED = "已退回";
    public static final List<String> DOSING_RECORD_STATUSES =
            List.of(DOSING_PENDING, DOSING_CONFIRMED, DOSING_ANOMALY, DOSING_RETURNED);

    private static final Map<String, List<String>> DOSING_RECORD_NEXT = Map.of(
            DOSING_PENDING, List.of(DOSING_CONFIRMED, DOSING_ANOMALY),
            DOSING_ANOMALY, List.of(DOSING_CONFIRMED, DOSING_RETURNED));

    // ---------------- 超标处置：待处置 → 执行中 → 已解除 ----------------
    public static final String DISPOSAL_PENDING = "待处置";
    public static final String DISPOSAL_RUNNING = "执行中";
    public static final String DISPOSAL_RESOLVED = "已解除";
    public static final List<String> DISPOSAL_STATUSES =
            List.of(DISPOSAL_PENDING, DISPOSAL_RUNNING, DISPOSAL_RESOLVED);

    /** 未解除（占用水源唯一槽位）的处置状态 */
    public static final List<String> DISPOSAL_ACTIVE = List.of(DISPOSAL_PENDING, DISPOSAL_RUNNING);

    private static final Map<String, List<String>> DISPOSAL_NEXT = Map.of(
            DISPOSAL_PENDING, List.of(DISPOSAL_RUNNING),
            DISPOSAL_RUNNING, List.of(DISPOSAL_RESOLVED));

    // ---------------- 处置-设备关联状态 ----------------
    public static final String LINK_NORMAL = "正常";
    public static final String LINK_CONFLICT = "冲突";
    public static final String LINK_EXCLUDED = "已排除";
    public static final String LINK_REPLACED = "已改选";

    // ---------------- 处置关联设备类型 ----------------
    public static final String EQUIP_TANK = "沉淀池";
    public static final String EQUIP_FILTER = "滤池";

    /** 设备处于「运行」时才可被处置单关联 */
    public static final String EQUIP_RUNNING = "运行";

    // ---------------- 业务阈值（原水水质硬约束 + 软预警带） ----------------
    /** pH 下限 */
    public static final BigDecimal PH_MIN = BigDecimal.valueOf(6.5);
    /** pH 上限 */
    public static final BigDecimal PH_MAX = BigDecimal.valueOf(8.5);
    /** 浊度硬上限（NTU），必须小于本值 */
    public static final BigDecimal TURB_MAX = BigDecimal.valueOf(3);
    /** 余氯下限（mg/L） */
    public static final BigDecimal CL_MIN = BigDecimal.valueOf(0.3);
    /** 余氯上限（mg/L） */
    public static final BigDecimal CL_MAX = BigDecimal.valueOf(4);

    /** pH 软预警带下沿（6.5~6.8 预警） */
    public static final BigDecimal PH_WARN_LOW = BigDecimal.valueOf(6.8);
    /** pH 软预警带上沿（8.2~8.5 预警） */
    public static final BigDecimal PH_WARN_HIGH = BigDecimal.valueOf(8.2);
    /** 浊度软预警带下沿（2~3 预警） */
    public static final BigDecimal TURB_WARN = BigDecimal.valueOf(2);
    /** 余氯软预警带（0.3~0.5 或 3~4 预警） */
    public static final BigDecimal CL_WARN_LOW = BigDecimal.valueOf(0.5);
    public static final BigDecimal CL_WARN_HIGH = BigDecimal.valueOf(3);

    // ---------------- 取值方法 ----------------

    private static BigDecimal bd(double v) {
        return BigDecimal.valueOf(v);
    }

    public static boolean isValidRawWaterStatus(String status) {
        return status != null && RAW_WATER_STATUSES.contains(status);
    }

    public static boolean isValidTankStatus(String status) {
        return status != null && TANK_STATUSES.contains(status);
    }

    public static boolean canTransferTank(String from, String to) {
        return TANK_NEXT.getOrDefault(from, List.of()).contains(to);
    }

    public static boolean isValidFilterType(String type) {
        return type != null && FILTER_TYPES.contains(type);
    }

    public static boolean isValidFilterStatus(String status) {
        return status != null && FILTER_STATUSES.contains(status);
    }

    /** 滤池：运行 → 反洗 → 运行 */
    public static boolean canTransferFilter(String from, String to) {
        return FILTER_NEXT.getOrDefault(from, List.of()).contains(to);
    }

    public static boolean isValidChemName(String name) {
        return name != null && CHEM_NAMES.contains(name);
    }

    /** 处置单状态机：只允许 待处置 → 执行中 → 已解除 顺向流转 */
    public static boolean canTransferDisposal(String from, String to) {
        return DISPOSAL_NEXT.getOrDefault(from, List.of()).contains(to);
    }

    // ---------------- 角色与加药批次取值方法 ----------------

    public static boolean isAdmin(String role) {
        return ROLE_ADMIN.equals(role);
    }

    /** 加药批次登记 / 维护：运行工或管理员 */
    public static boolean canRegisterDosing(String role) {
        return ROLE_OPERATOR.equals(role) || ROLE_ADMIN.equals(role);
    }

    public static boolean isValidConfigType(String itemType) {
        return itemType != null && CONFIG_TYPES.contains(itemType);
    }

    /** 加药批次状态机：待确认 → 已确认 / 异常待复核；异常待复核 → 已确认 / 已退回 */
    public static boolean canTransferDosingRecord(String from, String to) {
        return DOSING_RECORD_NEXT.getOrDefault(from, List.of()).contains(to);
    }

    /**
     * 原水建档时的硬校验：pH 6.5~8.5、浊度 < 3、余氯 0.3~4。任一不满足抛业务异常。
     *
     * @return 整理后的中文提示错误信息；为 null 表示通过
     */
    public static String checkRawWater(WaterSample ws) {
        if (ws.getPh() == null || ws.getTurbidityNtu() == null || ws.getChlorineMgL() == null) {
            return "水样 pH / 浊度 / 余氯 均需填写";
        }
        if (ws.getPh().compareTo(PH_MIN) < 0 || ws.getPh().compareTo(PH_MAX) > 0) {
            return "原水 pH 必须在 6.5~8.5 之间，当前 " + ws.getPh();
        }
        if (ws.getTurbidityNtu().compareTo(TURB_MAX) >= 0) {
            return "原水浊度必须 < 3 NTU，当前 " + ws.getTurbidityNtu() + " NTU";
        }
        if (ws.getChlorineMgL().compareTo(CL_MIN) < 0 || ws.getChlorineMgL().compareTo(CL_MAX) > 0) {
            return "原水余氯应在 0.3~4 mg/L 之间，当前 " + ws.getChlorineMgL();
        }
        return null;
    }

    /**
     * 通过硬校验后自动判级：处于软预警带内判「预警」，否则「合格」。
     * 「超标」仅可由管理员在 {@code /reclassify} 手动置为，用于复核留痕。
     */
    public static String classifyRawWater(WaterSample ws) {
        boolean warn = (ws.getPh().compareTo(PH_MIN) >= 0 && ws.getPh().compareTo(PH_WARN_LOW) < 0)
                || (ws.getPh().compareTo(PH_WARN_HIGH) > 0 && ws.getPh().compareTo(PH_MAX) <= 0)
                || (ws.getTurbidityNtu().compareTo(TURB_WARN) >= 0 && ws.getTurbidityNtu().compareTo(TURB_MAX) < 0)
                || (ws.getChlorineMgL().compareTo(CL_MIN) > 0 && ws.getChlorineMgL().compareTo(CL_WARN_LOW) <= 0)
                || (ws.getChlorineMgL().compareTo(CL_WARN_HIGH) > 0 && ws.getChlorineMgL().compareTo(CL_MAX) <= 0);
        return warn ? "预警" : "合格";
    }
}
