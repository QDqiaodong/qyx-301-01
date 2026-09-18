package com.example.salon;

import com.example.salon.entity.ActivityDemand;
import com.example.salon.entity.CustomerAccount;
import com.example.salon.entity.Invoice;
import com.example.salon.entity.RecommendResult;
import com.example.salon.entity.Venue;
import com.example.salon.repository.ActivityDemandRepository;
import com.example.salon.repository.CustomerAccountRepository;
import com.example.salon.repository.InvoiceRepository;
import com.example.salon.repository.RecommendResultRepository;
import com.example.salon.repository.VenueRepository;
import com.example.salon.service.DutyService;
import com.example.salon.service.LockService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup;

/**
 * 结算发票端到端（H2 + MockMvc）：
 * 锁场冻押金 → 销售/没结清/重复开票都被挡 → 开场后财务开票（金额=冻结额）→
 * 三处视图同票号同金额 → 销售不能作废、作废要原因 → 红字后重开，旧票失效、新票挂三处；
 * 全额实退按实退额开票；半退没收不能开。
 */
@SpringBootTest
@ActiveProfiles("it")
@Import(RedisMockConfig.class)
class InvoiceFlowIntegrationTest {

    @Autowired private WebApplicationContext wac;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private VenueRepository venueRepository;
    @Autowired private ActivityDemandRepository demandRepository;
    @Autowired private CustomerAccountRepository accountRepository;
    @Autowired private RecommendResultRepository recommendResultRepository;
    @Autowired private InvoiceRepository invoiceRepository;
    @Autowired private LockService lockService;
    @Autowired private DutyService dutyService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = webAppContextSetup(wac)
                .defaultResponseCharacterEncoding(java.nio.charset.StandardCharsets.UTF_8)
                .build();
        invoiceRepository.deleteAll();
        recommendResultRepository.deleteAll();
        demandRepository.deleteAll();
        venueRepository.deleteAll();
        accountRepository.deleteAll();
    }

    private long setupLockedDemand(String customer, String demandName, String price, LocalDateTime activityDate) {
        Venue venue = new Venue();
        venue.setName("场地-" + demandName);
        venue.setCapacity(100);
        venue.setPricePerDay(new BigDecimal(price));
        venue.setFacilities("投影仪,WiFi");
        venue.setActivityTypes("会议培训");
        venue.setDescription("测试场地");
        venue.setStatus(1);
        venue = venueRepository.save(venue);

        CustomerAccount account = accountRepository.findByCustomerNameAndCustomerPhone(customer, "13900000000")
                .orElseGet(() -> {
                    CustomerAccount a = new CustomerAccount();
                    a.setCustomerName(customer);
                    a.setCustomerPhone("13900000000");
                    a.setAvailableBalance(new BigDecimal("100000"));
                    a.setFrozenBalance(BigDecimal.ZERO);
                    return accountRepository.save(a);
                });

        ActivityDemand demand = new ActivityDemand();
        demand.setCustomerName(customer);
        demand.setCustomerPhone("13900000000");
        demand.setDemandName(demandName);
        demand.setExpectedDate(activityDate);
        demand.setExpectedPeople(50);
        demand.setActivityCategory("行业交流");
        demand.setBudgetMin(new BigDecimal("1000"));
        demand.setBudgetMax(new BigDecimal("10000"));
        demand.setRequiredFacilities("投影仪");
        demand.setSpecialRequirements("");
        demand.setMatchStatus(0);
        demand.setLocked(0);
        demand.setOpened(0);
        demand = demandRepository.save(demand);

        RecommendResult rr = new RecommendResult();
        rr.setDemandId(demand.getId());
        rr.setVenueId(venue.getId());
        rr.setVenueName(venue.getName());
        rr.setMatchScore(new BigDecimal("90"));
        rr.setCapacityScore(new BigDecimal("90"));
        rr.setFacilityScore(new BigDecimal("90"));
        rr.setActivityTypeScore(new BigDecimal("90"));
        rr.setBudgetScore(new BigDecimal("90"));
        rr.setRecommendOrder(1);
        rr.setReason("测试用推荐名单");
        recommendResultRepository.save(rr);

        lockService.confirmLock(demand.getId(), venue.getId());
        return demand.getId();
    }

    private String errMsg(MvcResult result) throws Exception {
        String content = result.getResponse().getContentAsString();
        if (!content.isBlank()) {
            JsonNode body = objectMapper.readTree(content);
            if (!body.path("message").isMissingNode() && !body.path("message").asText().isBlank()) {
                return body.path("message").asText();
            }
        }
        // MockMvc 不经过 /error 转发，中文原因挂在被解析的异常上
        if (result.getResolvedException() instanceof ResponseStatusException rse && rse.getReason() != null) {
            return rse.getReason();
        }
        return "";
    }

    @Test
    void fullInvoiceLifecycle_openedFrozen_thenVoidRed_thenReissue_andFullyRefunded_andHalfRefundBlocked() throws Exception {
        LocalDateTime future = LocalDateTime.now().plusDays(30);
        long demandId = setupLockedDemand("客户甲", "甲的沙龙", "3000", future);

        // ---- 1. 销售点开票：403，只有财务能开 ----
        MvcResult salesIssue = mockMvc.perform(post("/api/finance/invoices/demand/" + demandId + "/issue")
                        .header("X-Operator-Role", "SALES"))
                .andExpect(status().isForbidden()).andReturn();
        assertTrue(errMsg(salesIssue).contains("只有财务角色"), errMsg(salesIssue));

        // 不带角色头同样被挡
        mockMvc.perform(post("/api/finance/invoices/demand/" + demandId + "/issue"))
                .andExpect(status().isForbidden());

        // ---- 2. 财务在锁场当天（没开场、没退完）开票：409，点明还差哪笔冻结没结 ----
        MvcResult unsettled = mockMvc.perform(post("/api/finance/invoices/demand/" + demandId + "/issue")
                        .header("X-Operator-Role", "FINANCE"))
                .andExpect(status().isConflict()).andReturn();
        String unsettledMsg = errMsg(unsettled);
        assertTrue(unsettledMsg.contains("¥3000"), unsettledMsg);
        assertTrue(unsettledMsg.contains("没有结清") || unsettledMsg.contains("尚未开场"), unsettledMsg);

        // ---- 3. 两岗签到后开场 ----
        dutyService.signIn(demandId, "PRIMARY", "值守张三");
        dutyService.signIn(demandId, "FLEX", "机动李四");
        dutyService.openVenue(demandId);

        // ---- 4. 财务开票成功：票面=冻结额 3000，客户=押金账户户主 ----
        MvcResult issuedRaw = mockMvc.perform(post("/api/finance/invoices/demand/" + demandId + "/issue")
                        .header("X-Operator-Role", "FINANCE")
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andReturn();
        if (issuedRaw.getResponse().getStatus() != 200
                && issuedRaw.getResolvedException() instanceof ResponseStatusException rse) {
            fail("开票应成功但被拒绝：" + rse.getStatusCode() + " " + rse.getReason());
        }
        MvcResult issued = issuedRaw;
        JsonNode invoice1 = objectMapper.readTree(issued.getResponse().getContentAsString());
        String no1 = invoice1.path("invoiceNo").asText();
        assertEquals(0, new BigDecimal("3000").compareTo(invoice1.path("amount").decimalValue()));
        assertEquals("客户甲", invoice1.path("customerName").asText());
        assertEquals("FROZEN_OPENED", invoice1.path("basisType").asText());
        assertEquals("VALID", invoice1.path("status").asText());
        assertTrue(no1.startsWith("FP"));

        // ---- 5. 已有有效票，重复开票：409 ----
        MvcResult duplicate = mockMvc.perform(post("/api/finance/invoices/demand/" + demandId + "/issue")
                        .header("X-Operator-Role", "FINANCE"))
                .andExpect(status().isConflict()).andReturn();
        assertTrue(errMsg(duplicate).contains("已有一张有效发票"), errMsg(duplicate));
        assertTrue(errMsg(duplicate).contains(no1), errMsg(duplicate));

        // ---- 6. 三处视图必须是同一张有效票号与同一金额 ----
        JsonNode demandList = objectMapper.readTree(mockMvc.perform(get("/api/demand"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        JsonNode demandNode = findDemand(demandList, demandId);
        assertEquals(no1, demandNode.path("currentInvoiceNo").asText());
        assertEquals(0, new BigDecimal("3000").compareTo(demandNode.path("currentInvoiceAmount").decimalValue()));
        assertEquals("VALID", demandNode.path("currentInvoiceStatus").asText());

        JsonNode ledger = objectMapper.readTree(mockMvc.perform(get("/api/finance/deposit-transactions"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        JsonNode freezeRow = findTx(ledger, demandId, "FREEZE");
        assertEquals(no1, freezeRow.path("currentInvoiceNo").asText());
        assertEquals(0, new BigDecimal("3000").compareTo(freezeRow.path("currentInvoiceAmount").decimalValue()));

        JsonNode invoiceLedger = objectMapper.readTree(mockMvc.perform(get("/api/finance/invoices"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        JsonNode ledgerRow = findInvoice(invoiceLedger, no1);
        assertEquals("VALID", ledgerRow.path("status").asText());
        assertEquals(0, new BigDecimal("3000").compareTo(ledgerRow.path("amount").decimalValue()));

        // ---- 7. 作废：销售 403；无原因 400；财务带原因成功（红字） ----
        long invoice1Id = invoice1.path("id").asLong();
        mockMvc.perform(post("/api/finance/invoices/" + invoice1Id + "/void")
                        .header("X-Operator-Role", "SALES")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"voidReason\":\"x\"}"))
                .andExpect(status().isForbidden());

        mockMvc.perform(post("/api/finance/invoices/" + invoice1Id + "/void")
                        .header("X-Operator-Role", "FINANCE")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"voidReason\":\"   \"}"))
                .andExpect(status().isBadRequest());

        MvcResult voided = mockMvc.perform(post("/api/finance/invoices/" + invoice1Id + "/void")
                        .header("X-Operator-Role", "FINANCE")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"voidReason\":\"客户抬头开错，需要重开\"}"))
                .andExpect(status().isOk()).andReturn();
        JsonNode red = objectMapper.readTree(voided.getResponse().getContentAsString());
        assertEquals("RED_VOID", red.path("status").asText());
        assertTrue(red.path("activeFlag").isNull());
        assertEquals("客户抬头开错，需要重开", red.path("voidReason").asText());

        // 旧票号在台账里仍是红字留痕，但已不是有效票
        Invoice oldEntity = invoiceRepository.findById(invoice1Id).orElseThrow();
        assertEquals(Invoice.STATUS_RED_VOID, oldEntity.getStatus());
        assertNull(oldEntity.getActiveFlag());

        // ---- 8. 重开：新票才是当前有效票，金额仍 3000；三处挂新票号，旧票号不再当有效票 ----
        MvcResult reissued = mockMvc.perform(post("/api/finance/invoices/demand/" + demandId + "/issue")
                        .header("X-Operator-Role", "FINANCE"))
                .andExpect(status().isOk()).andReturn();
        JsonNode invoice2 = objectMapper.readTree(reissued.getResponse().getContentAsString());
        String no2 = invoice2.path("invoiceNo").asText();
        assertNotEquals(no1, no2);
        assertEquals(0, new BigDecimal("3000").compareTo(invoice2.path("amount").decimalValue()));
        assertEquals("VALID", invoice2.path("status").asText());

        JsonNode demandList2 = objectMapper.readTree(mockMvc.perform(get("/api/demand")).andReturn().getResponse().getContentAsString());
        assertEquals(no2, findDemand(demandList2, demandId).path("currentInvoiceNo").asText());

        JsonNode ledger2 = objectMapper.readTree(mockMvc.perform(get("/api/finance/deposit-transactions")).andReturn().getResponse().getContentAsString());
        assertEquals(no2, findTx(ledger2, demandId, "FREEZE").path("currentInvoiceNo").asText());

        // 旧票号查不到任何有效票
        assertEquals(0, invoiceRepository.findAll().stream()
                .filter(i -> i.getInvoiceNo().equals(no1) && i.getStatus().equals(Invoice.STATUS_VALID)).count());
        assertEquals(1, invoiceRepository.findAll().stream()
                .filter(i -> i.getDemandId().equals(demandId) && i.getStatus().equals(Invoice.STATUS_VALID)).count());

        // ==================== 全额实退场景 ====================
        long refundedDemand = setupLockedDemand("客户乙", "乙的沙龙", "2000", LocalDateTime.now().plusDays(30));
        lockService.releaseLock(refundedDemand); // 解除重配：100% 实退
        MvcResult refundedIssue = mockMvc.perform(post("/api/finance/invoices/demand/" + refundedDemand + "/issue")
                        .header("X-Operator-Role", "FINANCE"))
                .andExpect(status().isOk()).andReturn();
        JsonNode refundedInvoice = objectMapper.readTree(refundedIssue.getResponse().getContentAsString());
        assertEquals(0, new BigDecimal("2000").compareTo(refundedInvoice.path("amount").decimalValue()));
        assertEquals("FULLY_REFUNDED", refundedInvoice.path("basisType").asText());
        assertTrue(refundedInvoice.path("settlementTransactionId").asLong() > 0);

        // ==================== 半退没收场景：不能开 ====================
        long halfDemand = setupLockedDemand("客户丙", "丙的沙龙", "1000", LocalDateTime.now().plusDays(1));
        lockService.cancelActivity(halfDemand); // 明天活动今天取消 → 退一半没收一半
        MvcResult halfBlocked = mockMvc.perform(post("/api/finance/invoices/demand/" + halfDemand + "/issue")
                        .header("X-Operator-Role", "FINANCE"))
                .andExpect(status().isConflict()).andReturn();
        String halfMsg = errMsg(halfBlocked);
        assertTrue(halfMsg.contains("没收") || halfMsg.contains("¥500"), halfMsg);
        assertEquals(0, invoiceRepository.findAll().stream()
                .filter(i -> i.getDemandId().equals(halfDemand)).count());
    }

    private JsonNode findDemand(JsonNode array, long demandId) {
        for (JsonNode node : array) {
            if (node.path("id").asLong() == demandId) {
                return node;
            }
        }
        throw new AssertionError("需求 " + demandId + " 不在列表里");
    }

    private JsonNode findTx(JsonNode array, long demandId, String type) {
        for (JsonNode node : array) {
            if (node.path("demandId").asLong() == demandId && type.equals(node.path("type").asText())) {
                return node;
            }
        }
        throw new AssertionError("需求 " + demandId + " 的 " + type + " 流水不在台账里");
    }

    private JsonNode findInvoice(JsonNode array, String invoiceNo) {
        for (JsonNode node : array) {
            if (invoiceNo.equals(node.path("invoiceNo").asText())) {
                return node;
            }
        }
        throw new AssertionError("发票 " + invoiceNo + " 不在台账里");
    }
}
