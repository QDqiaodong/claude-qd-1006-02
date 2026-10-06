package com.waterworks.plant.entity;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import org.hibernate.annotations.Type;

import com.waterworks.plant.type.WaterSample;
import com.waterworks.plant.type.WaterSampleType;

import lombok.Getter;
import lombok.Setter;

/**
 * 原水监测：取水口取样的水质记录。
 *
 * <p>水质 {@link WaterSample} 是一个值对象，由自定义 {@link WaterSampleType} 持久化为单列
 * VARCHAR（{@code ph|turbidity|chlorine}）。状态：合格 / 预警 / 超标。</p>
 */
@Getter
@Setter
@Entity
@Table(name = "raw_water")
public class RawWater {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 水源 / 取水口名称 */
    @Column(name = "source_name", nullable = false, length = 32)
    private String sourceName;

    /** 取样时间 */
    @Column(name = "sample_at")
    private LocalDateTime sampleAt;

    /** 水质值对象，单列 VARCHAR 存储 */
    @Type(WaterSampleType.class)
    @Column(name = "water_sample", length = 64)
    private WaterSample waterSample;

    /** 合格 / 预警 / 超标 */
    @Column(name = "status", nullable = false, length = 16)
    private String status;

    @Column(name = "created_at")
    private LocalDateTime createdAt;
}
