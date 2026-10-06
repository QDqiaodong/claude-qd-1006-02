package com.waterworks.plant.type;

import java.io.Serializable;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;

import org.hibernate.engine.spi.SharedSessionContractImplementor;
import org.hibernate.usertype.UserType;

/**
 * 自定义 Hibernate {@link UserType}：把值对象 {@link WaterSample} 映射到单列 VARCHAR。
 *
 * <p>本 scaffold 的 Hibernate 6.5 中 {@code org.hibernate.usertype.UserType} 为泛型接口
 * {@code UserType<J>}，需实现 {@code getSqlType / returnedClass / nullSafeGet(int position) /
 * nullSafeSet(int index) / deepCopy / isMutable / disassemble(J) / assemble(Serializable, J)} 等签名。</p>
 *
 * <p>序列化格式 {@code ph|turbidityNtu|chlorineMgL}（见 {@link WaterSample#encode()} /
 * {@link WaterSample#parse(String)}）。</p>
 */
public class WaterSampleType implements UserType<WaterSample> {

    @Override
    public int getSqlType() {
        return Types.VARCHAR;
    }

    @Override
    public Class<WaterSample> returnedClass() {
        return WaterSample.class;
    }

    @Override
    public boolean equals(WaterSample x, WaterSample y) {
        if (x == y) {
            return true;
        }
        if (x == null || y == null) {
            return false;
        }
        return x.equals(y);
    }

    @Override
    public int hashCode(WaterSample x) {
        return x == null ? 0 : x.hashCode();
    }

    @Override
    public WaterSample nullSafeGet(ResultSet rs, int position,
            SharedSessionContractImplementor session, Object owner) throws SQLException {
        String raw = rs.getString(position);
        if (rs.wasNull() || raw == null) {
            return null;
        }
        return WaterSample.parse(raw);
    }

    @Override
    public void nullSafeSet(PreparedStatement st, WaterSample value, int index,
            SharedSessionContractImplementor session) throws SQLException {
        if (value == null) {
            st.setNull(index, Types.VARCHAR);
        } else {
            st.setString(index, value.encode());
        }
    }

    @Override
    public WaterSample deepCopy(WaterSample value) {
        if (value == null) {
            return null;
        }
        return new WaterSample(value.getPh(), value.getTurbidityNtu(), value.getChlorineMgL());
    }

    @Override
    public boolean isMutable() {
        return true;
    }

    @Override
    public Serializable disassemble(WaterSample value) {
        return value == null ? null : value.encode();
    }

    @Override
    public WaterSample assemble(Serializable cached, Object owner) {
        return cached == null ? null : WaterSample.parse((String) cached);
    }
}
