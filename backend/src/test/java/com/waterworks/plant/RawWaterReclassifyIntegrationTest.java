package com.waterworks.plant;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration;
import org.springframework.boot.autoconfigure.data.redis.RedisRepositoriesAutoConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.waterworks.plant.entity.RawWater;
import com.waterworks.plant.repository.RawWaterReclassifyLogRepository;
import com.waterworks.plant.repository.RawWaterRepository;
import com.waterworks.plant.type.WaterSample;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 原水人工重判越权修复的端到端验证：
 * 管理员成功留痕、普通角色越权、身份不可确认、请求重放、并发裁决，
 * 以及正常样本查询与自动判定不受影响。
 */
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:plant_rc;MODE=MySQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000")
@AutoConfigureMockMvc
@EnableAutoConfiguration(exclude = {RedisAutoConfiguration.class, RedisRepositoriesAutoConfiguration.class})
class RawWaterReclassifyIntegrationTest {

    @Autowired
    private MockMvc mvc;
    @Autowired
    private RawWaterRepository rawWaterRepository;
    @Autowired
    private RawWaterReclassifyLogRepository logRepository;
    @Autowired
    private ObjectMapper objectMapper;

    /** 合格区水样：自动判级为「合格」 */
    private WaterSample qualifiedSample() {
        return new WaterSample(new BigDecimal("7.30"), new BigDecimal("1.20"), new BigDecimal("1.00"));
    }

    /** 软预警带水样：浊度 2.40 ≥ 2，自动判级为「预警」 */
    private WaterSample warningSample() {
        return new WaterSample(new BigDecimal("7.10"), new BigDecimal("2.40"), new BigDecimal("0.45"));
    }

    private RawWater saveSample(String name, String status, WaterSample ws) {
        RawWater rw = new RawWater();
        rw.setSourceName(name);
        rw.setWaterSample(ws);
        rw.setStatus(status);
        rw.setSampleAt(java.time.LocalDateTime.now());
        rw.setCreatedAt(java.time.LocalDateTime.now());
        rw.setUpdatedAt(java.time.LocalDateTime.now());
        return rawWaterRepository.saveAndFlush(rw);
    }

    private String reqJson(Map<String, String> fields) throws Exception {
        return objectMapper.writeValueAsString(fields);
    }

    private MvcResult reclassify(long id, String body) throws Exception {
        return mvc.perform(post("/api/raw-waters/" + id + "/reclassify")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andReturn();
    }

    private JsonNode data(MvcResult r) throws Exception {
        // MockMvc 默认按 ISO-8859-1 解析响应，中文会乱码，统一按字节以 UTF-8 解析
        return objectMapper.readTree(r.getResponse().getContentAsByteArray()).path("data");
    }

    private String body(MvcResult r) throws Exception {
        return new String(r.getResponse().getContentAsByteArray(), java.nio.charset.StandardCharsets.UTF_8);
    }

    @BeforeEach
    void clean() {
        logRepository.deleteAll();
        rawWaterRepository.deleteAll();
    }

    private Map<String, String> adminBody(String from, String to, String requestId) {
        return Map.of("status", to, "operator", "李管理", "reason", "复检确认浊度异常，判为超标并启动复核",
                "role", "管理员", "expectedStatus", from, "requestId", requestId);
    }

    // ==================== 1. 管理员成功重判：原因必填 + 一条不可覆盖的裁决日志 ====================

    @Test
    void adminReclassifyCreatesImmutableLogWithBeforeAfterOperatorTimeReason() throws Exception {
        RawWater rw = saveSample("长江取水泵房", "合格", qualifiedSample());
        String reqId = "rc-success-" + UUID.randomUUID();

        MvcResult r = reclassify(rw.getId(), reqJson(adminBody("合格", "超标", reqId)));
        assertEquals(200, r.getResponse().getStatus(), r.getResponse().getContentAsString());

        // 样本结论与更新时间已变化，返回详情带一条裁决日志
        JsonNode d = data(r);
        assertEquals("超标", d.path("status").asText());
        assertNotNull(d.path("updatedAt").asText(null));
        assertEquals(1, d.path("logs").size());
        JsonNode log = d.path("logs").get(0);
        assertEquals("合格", log.path("fromStatus").asText());
        assertEquals("超标", log.path("toStatus").asText());
        assertEquals("李管理", log.path("operatorName").asText());
        assertTrue(log.path("reason").asText().contains("复检确认浊度异常"));
        assertNotNull(log.path("createdAt").asText(null));
        assertEquals(reqId, log.path("requestId").asText());

        RawWater reloaded = rawWaterRepository.findById(rw.getId()).orElseThrow();
        assertEquals("超标", reloaded.getStatus());
        assertNotNull(reloaded.getUpdatedAt());
        assertEquals(1, logRepository.countByRawWaterId(rw.getId()));

        // 连续第二次管理员重判：独立追加第二条日志，每次裁决都可独立追溯，不覆盖第一条
        String reqId2 = "rc-success-" + UUID.randomUUID();
        MvcResult r2 = reclassify(rw.getId(), reqJson(Map.of(
                "status", "合格", "operator", "李管理", "reason", "复检恢复正常，复位合格",
                "role", "管理员", "expectedStatus", "超标", "requestId", reqId2)));
        assertEquals(200, r2.getResponse().getStatus(), r2.getResponse().getContentAsString());
        JsonNode d2 = data(r2);
        assertEquals(2, d2.path("logs").size());
        assertEquals("超标", d2.path("logs").get(0).path("toStatus").asText());
        // 第二条日志独立记录第二次裁决：超标 → 合格
        assertEquals("超标", d2.path("logs").get(1).path("fromStatus").asText());
        assertEquals("合格", d2.path("logs").get(1).path("toStatus").asText());
        // 第一条日志原样保留，未被覆盖
        assertEquals("复检确认浊度异常，判为超标并启动复核",
                d2.path("logs").get(0).path("reason").asText());
        assertEquals(2, logRepository.countByRawWaterId(rw.getId()));
    }

    @Test
    void adminMustFillReason() throws Exception {
        RawWater rw = saveSample("一号取水口", "合格", qualifiedSample());
        MvcResult r = reclassify(rw.getId(), reqJson(Map.of(
                "status", "超标", "operator", "李管理", "reason", "  ",
                "role", "管理员", "expectedStatus", "合格", "requestId", "rc-no-reason")));
        assertEquals(400, r.getResponse().getStatus());
        assertTrue(body(r).contains("操作原因"));
        // 结论 / 更新时间 / 日志均不变
        assertEquals("合格", rawWaterRepository.findById(rw.getId()).orElseThrow().getStatus());
        assertEquals(0, logRepository.countByRawWaterId(rw.getId()));
    }

    // ==================== 2. 普通角色越权：明确拒绝，数据不变 ====================

    @Test
    void nonAdminRolesAreForbiddenAndNothingChanges() throws Exception {
        for (String role : List.of("化验员", "运行工")) {
            RawWater rw = saveSample(role + "样本", "合格", qualifiedSample());
            var before = rawWaterRepository.findById(rw.getId()).orElseThrow();
            MvcResult r = reclassify(rw.getId(), reqJson(Map.of(
                    "status", "超标", "operator", role, "reason", "尝试越权重判",
                    "role", role, "expectedStatus", "合格",
                    "requestId", "rc-forbidden-" + role)));
            assertEquals(403, r.getResponse().getStatus(), role + " 不应被允许重判");
            assertTrue(body(r).contains("仅管理员"));
            var after = rawWaterRepository.findById(rw.getId()).orElseThrow();
            assertEquals("合格", after.getStatus(), role + " 越权后结论不得变化");
            assertEquals(before.getUpdatedAt(), after.getUpdatedAt(), role + " 越权后更新时间不得变化");
            assertEquals(0, logRepository.countByRawWaterId(rw.getId()), role + " 越权不得产生日志");
        }
    }

    // ==================== 3. 身份不可确认：缺角色 / 空白角色 / 伪造角色，一律拒绝 ====================

    @Test
    void missingOrUnrecognizedIdentityIsForbidden() throws Exception {
        RawWater rw = saveSample("二号取水口", "预警", warningSample());
        var before = rawWaterRepository.findById(rw.getId()).orElseThrow();

        for (Map<String, String> body : List.of(
                // 完全不带 role
                Map.of("status", "超标", "operator", "某人", "reason", "无角色字段",
                        "expectedStatus", "预警", "requestId", "rc-no-role"),
                // role 空白
                Map.of("status", "超标", "operator", "某人", "reason", "空白角色",
                        "role", "  ", "expectedStatus", "预警", "requestId", "rc-blank-role"),
                // 伪造的角色名
                Map.of("status", "超标", "operator", "某人", "reason", "伪造超级管理员",
                        "role", "超级管理员", "expectedStatus", "预警", "requestId", "rc-fake-role"))) {
            MvcResult r = reclassify(rw.getId(), reqJson(body));
            assertEquals(403, r.getResponse().getStatus(),
                    "身份不可确认必须拒绝：" + body.get("role"));
            assertTrue(body(r).contains("仅管理员"));
        }
        var after = rawWaterRepository.findById(rw.getId()).orElseThrow();
        assertEquals("预警", after.getStatus());
        assertEquals(before.getUpdatedAt(), after.getUpdatedAt());
        assertEquals(0, logRepository.countByRawWaterId(rw.getId()));
    }

    // ==================== 4. 请求重放 / 快速连点：一次裁决一份日志 ====================

    @Test
    void identicalReplayAndRapidClicksProduceSingleLogAndReuseResult() throws Exception {
        RawWater rw = saveSample("长江取水泵房", "合格", qualifiedSample());
        String reqId = "rc-replay-" + UUID.randomUUID();
        String body = reqJson(adminBody("合格", "超标", reqId));

        MvcResult first = reclassify(rw.getId(), body);
        assertEquals(200, first.getResponse().getStatus());
        long firstLogId = data(first).path("logs").get(0).path("id").asLong();

        // 同一请求重放多次（含快速连点）：全部成功复用同一结果
        for (int i = 0; i < 5; i++) {
            MvcResult replay = reclassify(rw.getId(), body);
            assertEquals(200, replay.getResponse().getStatus(), "重放应安全复用首次结果");
            JsonNode d = data(replay);
            assertEquals("超标", d.path("status").asText());
            assertEquals(1, d.path("logs").size(), "重放不得追加日志");
            assertEquals(firstLogId, d.path("logs").get(0).path("id").asLong(),
                    "重放必须对应同一条裁决日志");
        }
        assertEquals(1, logRepository.countByRawWaterId(rw.getId()), "一次裁决只允许一份日志");
    }

    @Test
    void replayWithDifferentParametersIsConflictAndDoesNotOverwrite() throws Exception {
        RawWater rw = saveSample("一号取水口", "合格", qualifiedSample());
        String reqId = "rc-replay-diff-" + UUID.randomUUID();
        MvcResult first = reclassify(rw.getId(), reqJson(adminBody("合格", "超标", reqId)));
        assertEquals(200, first.getResponse().getStatus());

        // 同一令牌、换重判目标 → 409，明确冲突，原裁决与日志不变
        MvcResult tampered = reclassify(rw.getId(), reqJson(Map.of(
                "status", "合格", "operator", "李管理", "reason", "复用令牌换结论",
                "role", "管理员", "expectedStatus", "合格", "requestId", reqId)));
        assertEquals(409, tampered.getResponse().getStatus());
        assertTrue(body(tampered).contains("已完成过一次裁决"));

        assertEquals("超标", rawWaterRepository.findById(rw.getId()).orElseThrow().getStatus());
        assertEquals(1, logRepository.countByRawWaterId(rw.getId()));
        assertEquals("超标", logRepository.findByRawWaterIdOrderById(rw.getId()).get(0).getToStatus());
    }

    @Test
    void concurrentReplaysOfSameRequestProduceExactlyOneLog() throws Exception {
        RawWater rw = saveSample("二号取水口", "合格", qualifiedSample());
        String reqId = "rc-concurrent-replay-" + UUID.randomUUID();
        String body = reqJson(adminBody("合格", "超标", reqId));

        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<MvcResult>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            futures.add(pool.submit(() -> {
                start.await();
                return reclassify(rw.getId(), body);
            }));
        }
        start.countDown();
        AtomicInteger ok = new AtomicInteger();
        for (Future<MvcResult> f : futures) {
            MvcResult r = f.get(30, TimeUnit.SECONDS);
            assertEquals(200, r.getResponse().getStatus(),
                    "同请求并发重放都应安全复用：" + r.getResponse().getContentAsString());
            ok.incrementAndGet();
        }
        pool.shutdown();
        assertEquals(threads, ok.get());
        assertEquals(1, logRepository.countByRawWaterId(rw.getId()), "并发重放只能落一份日志");
        assertEquals("超标", rawWaterRepository.findById(rw.getId()).orElseThrow().getStatus());
    }

    // ==================== 5. 并发裁决：后到者不得静默覆盖先到者 ====================

    @Test
    void concurrentDistinctAdjudicationsSerializeAndLoserGetsConflict() throws Exception {
        RawWater rw = saveSample("长江取水泵房", "合格", qualifiedSample());

        // 两个管理员同时基于「合格」发起不同结论的裁决：一个判超标、一个复位合格（无效变化）
        String toExceed = "超标";
        String toQualified = "合格";
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<MvcResult>> futures = new ArrayList<>();
        for (String target : List.of(toExceed, toQualified)) {
            String body = reqJson(Map.of(
                    "status", target, "operator", "李管理", "reason", "并发裁决 → " + target,
                    "role", "管理员", "expectedStatus", "合格",
                    "requestId", "rc-concurrent-" + UUID.randomUUID()));
            futures.add(pool.submit(() -> {
                start.await();
                return reclassify(rw.getId(), body);
            }));
        }
        start.countDown();
        List<Integer> statuses = new ArrayList<>();
        for (Future<MvcResult> f : futures) {
            statuses.add(f.get(30, TimeUnit.SECONDS).getResponse().getStatus());
        }
        pool.shutdown();

        // 恰好一个成功（真正发生结论变化的），另一个得到一致的冲突 / 无变化提示
        long success = statuses.stream().filter(s -> s == 200).count();
        long rejected = statuses.stream().filter(s -> s == 409).count();
        assertEquals(1, success, "只允许一次裁决生效");
        assertEquals(1, rejected, "后到者必须得到 409 冲突提示");

        RawWater after = rawWaterRepository.findById(rw.getId()).orElseThrow();
        var logs = logRepository.findByRawWaterIdOrderById(rw.getId());
        assertEquals(1, logs.size(), "并发裁决只允许一份日志");
        // 最终结论必须与唯一一条日志的 toStatus 一致，不允许「结论与日志顺序矛盾」
        assertEquals(after.getStatus(), logs.get(0).getToStatus());
    }

    @Test
    void staleExpectedStatusAfterAnotherAdjudicationIsConflict() throws Exception {
        RawWater rw = saveSample("一号取水口", "合格", qualifiedSample());
        // 先由管理员 A 判超标
        MvcResult a = reclassify(rw.getId(), reqJson(adminBody("合格", "超标", "rc-A-" + UUID.randomUUID())));
        assertEquals(200, a.getResponse().getStatus());
        // 管理员 B 的页面仍停留在「合格」，带着陈旧结论重判 → 409，不静默覆盖
        MvcResult b = reclassify(rw.getId(), reqJson(Map.of(
                "status", "合格", "operator", "王管理", "reason", "我看到的还是合格",
                "role", "管理员", "expectedStatus", "合格",
                "requestId", "rc-B-" + UUID.randomUUID())));
        assertEquals(409, b.getResponse().getStatus());
        assertTrue(body(b).contains("已被其他裁决改为"));
        assertEquals("超标", rawWaterRepository.findById(rw.getId()).orElseThrow().getStatus());
        assertEquals(1, logRepository.countByRawWaterId(rw.getId()));

        // B 刷新后以最新结论「超标」为前提发起复位 → 正常成功，独立追加日志
        MvcResult b2 = reclassify(rw.getId(), reqJson(Map.of(
                "status", "合格", "operator", "王管理", "reason", "刷新后确认复检合格，复位",
                "role", "管理员", "expectedStatus", "超标",
                "requestId", "rc-B2-" + UUID.randomUUID())));
        assertEquals(200, b2.getResponse().getStatus());
        assertEquals(2, logRepository.countByRawWaterId(rw.getId()));
    }

    // ==================== 6. 正常查询与自动判定不受影响 ====================

    @Test
    void queryAndAutoClassificationAreUnaffected() throws Exception {
        RawWater qualified = saveSample("长江取水泵房", "合格", qualifiedSample());

        // 列表 / 详情查询不带任何身份即可用，且返回 updatedAt
        mvc.perform(get("/api/raw-waters")).andExpect(status().isOk());
        MvcResult got = mvc.perform(get("/api/raw-waters/" + qualified.getId()))
                .andExpect(status().isOk()).andReturn();
        JsonNode gd = data(got);
        assertEquals("合格", gd.path("status").asText());
        assertNotNull(gd.path("updatedAt").asText(null));

        // 裁决日志查询接口可用：初始为空
        MvcResult logsView = mvc.perform(get("/api/raw-waters/" + qualified.getId() + "/reclassify-logs"))
                .andExpect(status().isOk()).andReturn();
        assertEquals(0, data(logsView).path("logs").size());

        // 建档自动判级：合格区 → 合格；软预警带 → 预警。无需任何角色参数
        MvcResult c1 = mvc.perform(post("/api/raw-waters").contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"sourceName":"长江取水泵房","waterSample":{"ph":7.30,"turbidityNtu":1.20,"chlorineMgL":1.00}}
                        """))
                .andExpect(status().isOk()).andReturn();
        assertEquals("合格", data(c1).path("status").asText());

        MvcResult c2 = mvc.perform(post("/api/raw-waters").contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"sourceName":"二号取水口","waterSample":{"ph":7.10,"turbidityNtu":2.40,"chlorineMgL":0.45}}
                        """))
                .andExpect(status().isOk()).andReturn();
        assertEquals("预警", data(c2).path("status").asText());
    }
}
