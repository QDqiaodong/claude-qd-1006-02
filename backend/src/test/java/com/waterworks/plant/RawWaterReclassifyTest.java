package com.waterworks.plant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.waterworks.plant.common.BizException;
import com.waterworks.plant.entity.RawWater;
import com.waterworks.plant.entity.RawWaterJudgmentLog;
import com.waterworks.plant.repository.RawWaterJudgmentLogRepository;
import com.waterworks.plant.repository.RawWaterRepository;
import com.waterworks.plant.service.RawWaterService;
import com.waterworks.plant.type.WaterSample;

/**
 * 原水人工重判的自动化验证：
 *
 * <ul>
 *   <li>管理员成功重判：结论与更新时间变更，落一份完整裁决日志；</li>
 *   <li>普通角色（化验员 / 运行工）越权、身份不可确认（角色缺失 / 未知 / 操作人缺失）：
 *       明确拒绝，样本结论、更新时间、已有裁决日志均不变；</li>
 *   <li>请求重放（同 requestId）：幂等复用首次结果，只留一份日志；标识被挪用则冲突；</li>
 *   <li>并发裁决（同一样本）：串行化，一次裁决一份日志，后到请求得到一致冲突提示，
 *       最终结论与日志顺序一致；</li>
 *   <li>正常样本查询与建档自动判定不受影响。</li>
 * </ul>
 */
@SpringBootTest
@AutoConfigureMockMvc
class RawWaterReclassifyTest {

    private static final LocalDateTime FIXED = LocalDateTime.of(2026, 1, 1, 8, 0, 0);

    @Autowired
    private MockMvc mvc;
    @Autowired
    private ObjectMapper om;
    @Autowired
    private RawWaterRepository repository;
    @Autowired
    private RawWaterJudgmentLogRepository logRepository;
    @Autowired
    private RawWaterService service;

    @BeforeEach
    void clean() {
        logRepository.deleteAll();
        repository.deleteAll();
    }

    // ==================== 管理员成功 ====================

    @Test
    void adminReclassifySuccess() throws Exception {
        RawWater sample = newSample("长江取水泵房", "合格");

        mvc.perform(post("/api/raw-waters/{id}/reclassify", sample.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(req("超标", "合格", "李管理", "管理员", "复检浊度逼近上限，判定超标", "req-admin-1"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(true))
                .andExpect(jsonPath("$.data.status").value("超标"));

        RawWater after = repository.findById(sample.getId()).orElseThrow();
        assertThat(after.getStatus()).isEqualTo("超标");
        assertThat(after.getUpdatedAt()).isAfter(FIXED);

        List<RawWaterJudgmentLog> logs = logRepository.findByRawWaterIdOrderById(sample.getId());
        assertThat(logs).hasSize(1);
        RawWaterJudgmentLog log = logs.get(0);
        assertThat(log.getAction()).isEqualTo("人工重判");
        assertThat(log.getFromStatus()).isEqualTo("合格");
        assertThat(log.getToStatus()).isEqualTo("超标");
        assertThat(log.getOperatorName()).isEqualTo("李管理");
        assertThat(log.getReason()).isEqualTo("复检浊度逼近上限，判定超标");
        assertThat(log.getRequestId()).isEqualTo("req-admin-1");
        assertThat(log.getCreatedAt()).isNotNull();

        // 裁决日志可通过查询接口追溯
        mvc.perform(get("/api/raw-waters/{id}/judgment-logs", sample.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(true))
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].toStatus").value("超标"))
                .andExpect(jsonPath("$.data[0].reason").value("复检浊度逼近上限，判定超标"));
    }

    @Test
    void adminSequentialReclassifyAppendsIndependentLogs() throws Exception {
        RawWater sample = newSample("一号取水口", "合格");
        service.reclassify(sample.getId(), "超标", "合格", "李管理", "管理员", "首次复核判超标", "req-seq-1");
        // 基于最新结论的第二次重判是独立裁决：追加日志，不覆盖首条
        service.reclassify(sample.getId(), "合格", "超标", "王管理", "管理员", "处置完成复检合格", "req-seq-2");

        RawWater after = repository.findById(sample.getId()).orElseThrow();
        assertThat(after.getStatus()).isEqualTo("合格");
        List<RawWaterJudgmentLog> logs = logRepository.findByRawWaterIdOrderById(sample.getId());
        assertThat(logs).hasSize(2);
        assertThat(logs.get(0).getFromStatus()).isEqualTo("合格");
        assertThat(logs.get(0).getToStatus()).isEqualTo("超标");
        assertThat(logs.get(0).getOperatorName()).isEqualTo("李管理");
        assertThat(logs.get(1).getFromStatus()).isEqualTo("超标");
        assertThat(logs.get(1).getToStatus()).isEqualTo("合格");
        assertThat(logs.get(1).getOperatorName()).isEqualTo("王管理");
        // 最终结论与日志顺序一致
        assertThat(after.getStatus()).isEqualTo(logs.get(logs.size() - 1).getToStatus());
    }

    // ==================== 越权与身份不可确认 ====================

    @Test
    void nonAdminRolesAreRejectedAndNothingChanges() throws Exception {
        for (String role : new String[]{"化验员", "运行工"}) {
            RawWater sample = newSample("越权水源-" + role, "预警");

            mvc.perform(post("/api/raw-waters/{id}/reclassify", sample.getId())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(req("超标", "预警", "张三", role, "尝试越权重判", "req-bypass-" + role))))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.ok").value(false))
                    .andExpect(jsonPath("$.message").value(containsString("仅管理员")));

            assertUnchanged(sample.getId(), "预警");
        }
    }

    @Test
    void unverifiableIdentityIsRejectedAndNothingChanges() throws Exception {
        // 角色缺失
        RawWater s1 = newSample("身份水源-1", "合格");
        Map<String, String> noRole = req("超标", "合格", "张三", null, "原因", "req-noidentity-1");
        noRole.remove("role");
        mvc.perform(post("/api/raw-waters/{id}/reclassify", s1.getId())
                        .contentType(MediaType.APPLICATION_JSON).content(json(noRole)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.ok").value(false))
                .andExpect(jsonPath("$.message").value(containsString("无法确认操作身份")));
        assertUnchanged(s1.getId(), "合格");

        // 角色为空白
        RawWater s2 = newSample("身份水源-2", "合格");
        mvc.perform(post("/api/raw-waters/{id}/reclassify", s2.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(req("超标", "合格", "张三", "  ", "原因", "req-noidentity-2"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(containsString("无法确认操作身份")));
        assertUnchanged(s2.getId(), "合格");

        // 未知角色
        RawWater s3 = newSample("身份水源-3", "合格");
        mvc.perform(post("/api/raw-waters/{id}/reclassify", s3.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(req("超标", "合格", "张三", "厂长", "原因", "req-noidentity-3"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(containsString("仅管理员")));
        assertUnchanged(s3.getId(), "合格");

        // 操作人缺失
        RawWater s4 = newSample("身份水源-4", "合格");
        mvc.perform(post("/api/raw-waters/{id}/reclassify", s4.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(req("超标", "合格", " ", "管理员", "原因", "req-noidentity-4"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(containsString("操作人")));
        assertUnchanged(s4.getId(), "合格");
    }

    @Test
    void reasonIsRequiredForReclassify() throws Exception {
        RawWater sample = newSample("原因水源", "合格");
        mvc.perform(post("/api/raw-waters/{id}/reclassify", sample.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(req("超标", "合格", "李管理", "管理员", "  ", "req-noreason"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(containsString("操作原因")));
        assertUnchanged(sample.getId(), "合格");
    }

    // ==================== 请求重放 ====================

    @Test
    void replaySameRequestIsIdempotentWithSingleLog() throws Exception {
        RawWater sample = newSample("重放水源", "合格");
        String body = json(req("超标", "合格", "李管理", "管理员", "复检确认超标", "req-replay-1"));

        for (int i = 0; i < 3; i++) {
            mvc.perform(post("/api/raw-waters/{id}/reclassify", sample.getId())
                            .contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.ok").value(true))
                    .andExpect(jsonPath("$.data.status").value("超标"));
        }

        assertThat(repository.findById(sample.getId()).orElseThrow().getStatus()).isEqualTo("超标");
        // 一次裁决只产生一份日志
        assertThat(logRepository.findByRawWaterIdOrderById(sample.getId())).hasSize(1);
    }

    @Test
    void requestIdReusedWithDifferentParamsIsConflict() throws Exception {
        RawWater sample = newSample("标识水源", "合格");
        service.reclassify(sample.getId(), "超标", "合格", "李管理", "管理员", "首次裁决", "req-hijack-1");

        // 同一 requestId 携带不同目标结论重放 → 一致冲突提示，不重复留痕
        mvc.perform(post("/api/raw-waters/{id}/reclassify", sample.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(req("合格", "超标", "李管理", "管理员", "首次裁决", "req-hijack-1"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.ok").value(false))
                .andExpect(jsonPath("$.message").value(containsString("裁决冲突")));

        assertThat(repository.findById(sample.getId()).orElseThrow().getStatus()).isEqualTo("超标");
        assertThat(logRepository.findByRawWaterIdOrderById(sample.getId())).hasSize(1);
    }

    @Test
    void staleViewAndDuplicateTargetGetConsistentConflict() throws Exception {
        RawWater sample = newSample("冲突水源", "合格");
        service.reclassify(sample.getId(), "超标", "合格", "李管理", "管理员", "首次裁决", "req-stale-1");

        // 基于过期页面（仍以为当前是「合格」）再次重判 → 冲突，不覆盖先到裁决
        mvc.perform(post("/api/raw-waters/{id}/reclassify", sample.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(req("合格", "合格", "王管理", "管理员", "过期页面操作", "req-stale-2"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(containsString("裁决冲突")));

        // 目标与当前结论相同（连点 / 并发同目标）→ 冲突，不重复留痕
        mvc.perform(post("/api/raw-waters/{id}/reclassify", sample.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(req("超标", "超标", "王管理", "管理员", "重复判超标", "req-stale-3"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(containsString("裁决冲突")));

        RawWater after = repository.findById(sample.getId()).orElseThrow();
        assertThat(after.getStatus()).isEqualTo("超标");
        List<RawWaterJudgmentLog> logs = logRepository.findByRawWaterIdOrderById(sample.getId());
        assertThat(logs).hasSize(1);
        assertThat(logs.get(0).getRequestId()).isEqualTo("req-stale-1");
    }

    // ==================== 并发裁决 ====================

    @Test
    void concurrentSameRequestReplayKeepsSingleLog() throws Exception {
        RawWater sample = newSample("并发重放水源", "合格");
        int n = 8;
        List<Outcome> outcomes = runConcurrently(n, i ->
                service.reclassify(sample.getId(), "超标", "合格", "李管理", "管理员", "复检确认超标", "req-conc-same"));

        assertThat(outcomes.stream().filter(o -> o.error == null).count()).isEqualTo(n);
        RawWater after = repository.findById(sample.getId()).orElseThrow();
        assertThat(after.getStatus()).isEqualTo("超标");
        List<RawWaterJudgmentLog> logs = logRepository.findByRawWaterIdOrderById(sample.getId());
        assertThat(logs).hasSize(1);
        assertThat(after.getStatus()).isEqualTo(logs.get(0).getToStatus());
    }

    @Test
    void concurrentSameTargetAllowsExactlyOneJudgment() throws Exception {
        RawWater sample = newSample("并发同目标水源", "合格");
        int n = 8;
        List<Outcome> outcomes = runConcurrently(n, i ->
                service.reclassify(sample.getId(), "超标", "合格", "管理员-" + i, "管理员",
                        "并发裁决-" + i, "req-conc-target-" + i));

        long ok = outcomes.stream().filter(o -> o.error == null).count();
        long conflicts = outcomes.stream()
                .filter(o -> o.error != null && o.error.getMessage().contains("裁决冲突")).count();
        assertThat(ok).isEqualTo(1);
        assertThat(conflicts).isEqualTo(n - 1);

        RawWater after = repository.findById(sample.getId()).orElseThrow();
        assertThat(after.getStatus()).isEqualTo("超标");
        List<RawWaterJudgmentLog> logs = logRepository.findByRawWaterIdOrderById(sample.getId());
        assertThat(logs).hasSize(1);
        assertThat(logs.get(0).getToStatus()).isEqualTo("超标");
    }

    @Test
    void concurrentDifferentTargetsKeepStateAndLogsConsistent() throws Exception {
        RawWater sample = newSample("并发异目标水源", "合格");
        List<Outcome> outcomes = runConcurrently(2, i -> i == 0
                ? service.reclassify(sample.getId(), "超标", "合格", "李管理", "管理员", "判超标", "req-conc-diff-a")
                : service.reclassify(sample.getId(), "合格", "合格", "王管理", "管理员", "复位合格", "req-conc-diff-b"));

        long ok = outcomes.stream().filter(o -> o.error == null).count();
        long conflicts = outcomes.stream()
                .filter(o -> o.error != null && o.error.getMessage().contains("裁决冲突")).count();
        assertThat(ok).isEqualTo(1);
        assertThat(conflicts).isEqualTo(1);

        // 一次裁决一份日志；最终结论与唯一日志的重判后结论一致，后到操作未静默覆盖
        List<RawWaterJudgmentLog> logs = logRepository.findByRawWaterIdOrderById(sample.getId());
        assertThat(logs).hasSize(1);
        RawWater after = repository.findById(sample.getId()).orElseThrow();
        assertThat(after.getStatus()).isEqualTo(logs.get(0).getToStatus());
    }

    // ==================== 正常查询与自动判定不受影响 ====================

    @Test
    void queryAndAutoClassifyAreUnaffected() throws Exception {
        // 自动判定：常规值 → 合格
        mvc.perform(post("/api/raw-waters")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("sourceName", "自动判定水源-合格",
                                "waterSample", Map.of("ph", "7.20", "turbidityNtu", "1.00", "chlorineMgL", "1.00")))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(true))
                .andExpect(jsonPath("$.data.status").value("合格"));

        // 自动判定：软预警带内 → 预警
        mvc.perform(post("/api/raw-waters")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("sourceName", "自动判定水源-预警",
                                "waterSample", Map.of("ph", "8.30", "turbidityNtu", "1.00", "chlorineMgL", "1.00")))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("预警"));

        // 硬校验：超出可建档范围 → 拒绝
        mvc.perform(post("/api/raw-waters")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("sourceName", "自动判定水源-拒绝",
                                "waterSample", Map.of("ph", "9.00", "turbidityNtu", "1.00", "chlorineMgL", "1.00")))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.ok").value(false));

        // 列表与单条查询正常
        mvc.perform(get("/api/raw-waters"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(true))
                .andExpect(jsonPath("$.data.length()").value(2));
        mvc.perform(get("/api/raw-waters").param("status", "预警"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].sourceName").value("自动判定水源-预警"));
    }

    // ==================== 测试工具 ====================

    private RawWater newSample(String sourceName, String status) {
        RawWater rw = new RawWater();
        rw.setSourceName(sourceName);
        rw.setSampleAt(FIXED);
        rw.setWaterSample(new WaterSample(new BigDecimal("7.20"), new BigDecimal("1.00"), new BigDecimal("1.00")));
        rw.setStatus(status);
        rw.setCreatedAt(FIXED);
        rw.setUpdatedAt(FIXED);
        return repository.save(rw);
    }

    /** 拒绝后校验：样本结论、更新时间、裁决日志均不得变化 */
    private void assertUnchanged(Long id, String expectedStatus) {
        RawWater after = repository.findById(id).orElseThrow();
        assertThat(after.getStatus()).isEqualTo(expectedStatus);
        assertThat(after.getUpdatedAt()).isEqualTo(FIXED);
        assertThat(logRepository.findByRawWaterIdOrderById(id)).isEmpty();
    }

    private Map<String, String> req(String status, String fromStatus, String operator,
                                    String role, String reason, String requestId) {
        Map<String, String> m = new HashMap<>();
        m.put("status", status);
        m.put("fromStatus", fromStatus);
        m.put("operator", operator);
        m.put("role", role);
        m.put("reason", reason);
        m.put("requestId", requestId);
        return m;
    }

    private String json(Map<String, ?> body) throws Exception {
        return om.writeValueAsString(body);
    }

    private interface ThrowingCall {
        RawWater run(int index);
    }

    private record Outcome(RawWater value, BizException error) {
    }

    /** 多线程同时发起重判，收集每次调用结果（成功或业务异常） */
    private List<Outcome> runConcurrently(int n, ThrowingCall call) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(n);
        CountDownLatch ready = new CountDownLatch(n);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Outcome>> futures = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            int idx = i;
            futures.add(pool.submit(() -> {
                ready.countDown();
                start.await(5, TimeUnit.SECONDS);
                try {
                    return new Outcome(call.run(idx), null);
                } catch (BizException e) {
                    return new Outcome(null, e);
                }
            }));
        }
        assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
        start.countDown();
        pool.shutdown();
        assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).isTrue();
        List<Outcome> outcomes = new ArrayList<>();
        for (Future<Outcome> f : futures) {
            outcomes.add(f.get());
        }
        return outcomes;
    }
}
