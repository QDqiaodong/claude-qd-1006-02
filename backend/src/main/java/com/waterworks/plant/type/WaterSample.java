package com.waterworks.plant.type;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * 水质值对象：pH、浊度(NTU)、余氯(mg/L)。
 *
 * <p>由 {@link WaterSampleType} 持久化为单列 VARCHAR，格式 {@code ph|turbidityNtu|chlorineMgL}。</p>
 */
public class WaterSample {

    private final BigDecimal ph;
    private final BigDecimal turbidityNtu;
    private final BigDecimal chlorineMgL;

    public WaterSample(BigDecimal ph, BigDecimal turbidityNtu, BigDecimal chlorineMgL) {
        this.ph = ph;
        this.turbidityNtu = turbidityNtu;
        this.chlorineMgL = chlorineMgL;
    }

    public BigDecimal getPh() {
        return ph;
    }

    public BigDecimal getTurbidityNtu() {
        return turbidityNtu;
    }

    public BigDecimal getChlorineMgL() {
        return chlorineMgL;
    }

    /** 序列化为 {@code ph|turbidityNtu|chlorineMgL}。 */
    public String encode() {
        return String.join("|",
                ph == null ? "" : ph.toPlainString(),
                turbidityNtu == null ? "" : turbidityNtu.toPlainString(),
                chlorineMgL == null ? "" : chlorineMgL.toPlainString());
    }

    /** 从 {@code ph|turbidityNtu|chlorineMgL} 解析；空串返回 null。 */
    public static WaterSample parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String[] p = raw.split("\\|", -1);
        BigDecimal ph = p.length > 0 && !p[0].isEmpty() ? new BigDecimal(p[0]) : null;
        BigDecimal turbidityNtu = p.length > 1 && !p[1].isEmpty() ? new BigDecimal(p[1]) : null;
        BigDecimal chlorineMgL = p.length > 2 && !p[2].isEmpty() ? new BigDecimal(p[2]) : null;
        return new WaterSample(ph, turbidityNtu, chlorineMgL);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof WaterSample)) {
            return false;
        }
        WaterSample that = (WaterSample) o;
        return Objects.equals(ph, that.ph)
                && Objects.equals(turbidityNtu, that.turbidityNtu)
                && Objects.equals(chlorineMgL, that.chlorineMgL);
    }

    @Override
    public int hashCode() {
        return Objects.hash(ph, turbidityNtu, chlorineMgL);
    }

    @Override
    public String toString() {
        return encode();
    }
}
