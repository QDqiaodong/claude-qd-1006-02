package com.waterworks.plant.service;

import java.time.LocalDateTime;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.waterworks.plant.common.BizException;
import com.waterworks.plant.common.Dict;
import com.waterworks.plant.entity.RawWater;
import com.waterworks.plant.repository.RawWaterRepository;
import com.waterworks.plant.type.WaterSample;

/**
 * 原水监测业务。
 *
 * <p>核心规则：① 建档时 pH 必须 6.5~8.5、浊度 < 3 NTU、余氯 0.3~4 mg/L，否则拒绝建档；
 * ② 通过硬校验后按软预警带自动判「合格 / 预警」；③「超标」仅管理员可手动重判。</p>
 */
@Service
public class RawWaterService {

    private final RawWaterRepository repository;

    public RawWaterService(RawWaterRepository repository) {
        this.repository = repository;
    }

    public List<RawWater> list(String status, String sourceName) {
        if (status != null && !status.isBlank()) {
            return repository.findByStatus(status);
        }
        if (sourceName != null && !sourceName.isBlank()) {
            return repository.findBySourceName(sourceName);
        }
        return repository.findAll();
    }

    public RawWater get(Long id) {
        return repository.findById(id)
                .orElseThrow(() -> new BizException("原水监测记录不存在：" + id));
    }

    @Transactional
    public RawWater create(RawWater input) {
        if (input.getSourceName() == null || input.getSourceName().isBlank()) {
            throw new BizException("水源名称不能为空");
        }
        WaterSample ws = input.getWaterSample();
        if (ws == null) {
            throw new BizException("水质水样不能为空");
        }
        // 规则①：硬校验，不满足直接拒绝建档
        String fail = Dict.checkRawWater(ws);
        if (fail != null) {
            throw new BizException(fail);
        }
        RawWater rw = new RawWater();
        rw.setSourceName(input.getSourceName().trim());
        rw.setWaterSample(ws);
        rw.setSampleAt(input.getSampleAt() == null ? LocalDateTime.now() : input.getSampleAt());
        // 规则②：自动判级
        rw.setStatus(Dict.classifyRawWater(ws));
        rw.setCreatedAt(LocalDateTime.now());
        return repository.save(rw);
    }

    @Transactional
    public RawWater update(Long id, RawWater input) {
        RawWater rw = get(id);
        if (input.getSourceName() != null && !input.getSourceName().isBlank()) {
            rw.setSourceName(input.getSourceName().trim());
        }
        if (input.getWaterSample() != null) {
            rw.setWaterSample(input.getWaterSample());
        }
        if (input.getSampleAt() != null) {
            rw.setSampleAt(input.getSampleAt());
        }
        return repository.save(rw);
    }

    /** 管理员手动重判水质状态（含置为「超标」做复核留痕） */
    @Transactional
    public RawWater reclassify(Long id, String status) {
        RawWater rw = get(id);
        if (!Dict.isValidRawWaterStatus(status)) {
            throw new BizException("水质状态不合法：" + status);
        }
        rw.setStatus(status);
        return repository.save(rw);
    }
}
