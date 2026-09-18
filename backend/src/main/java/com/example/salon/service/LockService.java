package com.example.salon.service;

import com.example.salon.entity.ActivityDemand;
import com.example.salon.entity.DepositTransaction;
import com.example.salon.entity.LockRecord;
import com.example.salon.entity.RecommendResult;
import com.example.salon.entity.Venue;
import com.example.salon.repository.ActivityDemandRepository;
import com.example.salon.repository.LockRecordRepository;
import com.example.salon.repository.RecommendResultRepository;
import com.example.salon.repository.VenueRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * 场地锁定：客户在推荐结果里口头确认一家场地后，先冻结一笔押金，
 * 押金冻结成功需求才进入已锁定（待办活动）；冻结失败当场抛错、整笔回滚，
 * 需求不进待办、场地当天也不被占。
 * 锁定期间期望日期、人数、预算上限、必备设施冻结。
 * 取消活动按距活动日远近分档退押（{@link DepositService}）；解除重配、场地原因破裂全额退回。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class LockService {

    private final ActivityDemandRepository demandRepository;
    private final VenueRepository venueRepository;
    private final LockRecordRepository lockRecordRepository;
    private final RecommendResultRepository recommendResultRepository;
    private final DepositService depositService;

    @Transactional
    public LockRecord confirmLock(Long demandId, Long venueId) {
        ActivityDemand demand = demandRepository.findById(demandId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "需求不存在"));

        if (demand.getLocked() != null && demand.getLocked() == 1) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "该需求已锁定场地「" + demand.getLockedVenueName() + "」，请先解除锁定");
        }

        if (demand.getMatchStatus() != null && demand.getMatchStatus() == 6) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "该需求的活动已取消，不能再确认锁定场地；如需继续办活动请重新提交需求");
        }

        Venue venue = venueRepository.findById(venueId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "场地不存在"));
        if (venue.getStatus() == null || venue.getStatus() != 1) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "该场地已停用，不能确认锁定");
        }

        // 退押按取消日距活动日的天数分档，冻结前必须先定好活动日期
        if (demand.getExpectedDate() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "冻结押金需先确定活动日期：退押按取消时间距活动日远近分档，请补全期望日期后再确认场地");
        }

        // 只能确认当前有效推荐名单里的场地
        List<RecommendResult> results = recommendResultRepository.findByDemandIdOrderByRecommendOrder(demandId);
        if (demand.getMatchStatus() != null && demand.getMatchStatus() == 4) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "该需求待重配，原推荐名单已作废，请先按新条件重新计算推荐");
        }
        boolean inRecommend = results.stream().anyMatch(r -> Objects.equals(r.getVenueId(), venueId));
        if (!inRecommend) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "该场地不在当前推荐名单中，请重新计算推荐后再确认");
        }

        // 同一场地同一天只能有一条正式活动锁定
        java.time.LocalDate expectedDay = demand.getExpectedDate().toLocalDate();
        List<ActivityDemand> sameVenueLocks = demandRepository.findByLockedVenueIdAndLocked(venueId, 1);
        ActivityDemand blocker = sameVenueLocks.stream()
                .filter(d -> !Objects.equals(d.getId(), demandId))
                .filter(d -> d.getExpectedDate() != null
                        && d.getExpectedDate().toLocalDate().equals(expectedDay))
                .findFirst()
                .orElse(null);
        if (blocker != null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    String.format("场地「%s」在 %s 已被需求「%s」（需求ID：%d）锁定",
                            venue.getName(), expectedDay,
                            blocker.getDemandName(), blocker.getId()));
        }

        // 先冻结押金：余额不足会抛 409，整笔事务回滚——
        // 下面的占场、锁定记录都不会落库，冻结失败当天不能开场、场地不被空口占用。
        DepositTransaction freezeTx = depositService.freezeDeposit(demand, venue);

        demand.setLocked(1);
        demand.setLockedVenueId(venue.getId());
        demand.setLockedVenueName(venue.getName());
        demand.setLockedAt(LocalDateTime.now());
        demand.setLockBreakReason(null);
        demand.setMatchStatus(5);
        demand.setOpened(0);
        demand.setOpenedAt(null);
        demand.setDepositAmount(venue.getPricePerDay());
        demand.setDepositFreezeId(freezeTx.getId());
        demand.setDepositRefundSummary(null);
        demandRepository.save(demand);

        LockRecord record = new LockRecord();
        record.setDemandId(demandId);
        record.setDemandName(demand.getDemandName());
        record.setVenueId(venue.getId());
        record.setVenueName(venue.getName());
        record.setStatus("LOCKED");
        lockRecordRepository.save(record);

        log.info("需求ID {} 确认锁定场地「{}」，押金 ¥{} 已冻结",
                demandId, venue.getName(), venue.getPricePerDay().toPlainString());
        return record;
    }

    /**
     * 手动解除锁定（回炉改条件重配，非客户取消活动）：押金全额退回，
     * 作废当前推荐，需求回到待重配，之后可改数字并重新计算。
     */
    @Transactional
    public LockRecord releaseLock(Long demandId) {
        ActivityDemand demand = demandRepository.findById(demandId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "需求不存在"));
        if (demand.getLocked() == null || demand.getLocked() != 1) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "该需求当前未锁定");
        }

        String reason = "销售解除锁定回待重配（非客户取消活动），押金全额退回";
        DepositTransaction refundTx = depositService.releaseFullRefund(demand, reason);

        LockRecord record = closeActiveRecord(demandId, "RELEASED", reason);

        clearLockFields(demand);
        demand.setDepositRefundSummary(formatRefundSummary(refundTx));
        demand.setMatchStatus(4);
        demandRepository.save(demand);
        recommendResultRepository.deleteByDemandId(demandId);

        log.info("需求ID {} 手动解除锁定，押金全额退回，推荐已作废", demandId);
        return record;
    }

    /**
     * 客户取消活动：按取消日距活动日远近分档结算退押后释放场地。
     * 比例完全由 {@link DepositService} 按财务规则计算，接口不收比例参数，销售不能手改。
     * 活动取消后需求为终态（matchStatus=6）。
     */
    @Transactional
    public DepositTransaction cancelActivity(Long demandId) {
        ActivityDemand demand = demandRepository.findById(demandId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "需求不存在"));
        if (demand.getLocked() == null || demand.getLocked() != 1) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "该需求当前未锁定，无法取消活动");
        }

        DepositTransaction refundTx = depositService.cancelWithTieredRefund(demand, LocalDateTime.now());

        closeActiveRecord(demandId, "CANCELED",
                "客户取消活动：" + refundTx.getReason() + "；" + formatRefundSummary(refundTx));

        clearLockFields(demand);
        demand.setMatchStatus(6);
        demand.setDepositRefundSummary(formatRefundSummary(refundTx));
        demandRepository.save(demand);
        recommendResultRepository.deleteByDemandId(demandId);

        log.info("需求ID {} 取消活动，押金结算：{}", demandId, formatRefundSummary(refundTx));
        return refundTx;
    }

    @Transactional(readOnly = true)
    public DepositService.DepositPreview previewCancellation(Long demandId) {
        ActivityDemand demand = demandRepository.findById(demandId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "需求不存在"));
        return depositService.previewCancellation(demand);
    }

    @Transactional(readOnly = true)
    public List<LockRecord> getLockHistory(Long demandId) {
        return lockRecordRepository.findByDemandIdOrderByLockedAtDesc(demandId);
    }

    /**
     * 场地被停用、日租金抬过预算上限、或拆掉还在要的必备设施时，
     * 相关有效锁定自行破裂：押金全额退回（非客户违约），需求回到待重配，旧推荐作废。
     *
     * @return 破裂原因汇总（无破裂返回 null）
     */
    @Transactional
    public String breakLocksForVenueChange(Venue venue, Integer newStatus, BigDecimal newPrice, String newFacilities) {
        List<ActivityDemand> lockedDemands = demandRepository.findByLockedVenueIdAndLocked(venue.getId(), 1);
        StringBuilder summary = new StringBuilder();

        for (ActivityDemand demand : lockedDemands) {
            String reason = buildBreakReason(venue, demand, newStatus, newPrice, newFacilities);
            if (reason == null) {
                continue;
            }

            String refundReason = "场地侧原因导致锁定自行破裂（" + reason + "，非客户违约），押金全额退回";
            DepositTransaction refundTx = depositService.releaseFullRefund(demand, refundReason);

            closeActiveRecord(demand.getId(), "BROKEN", reason);
            clearLockFields(demand);
            demand.setMatchStatus(4);
            demand.setLockBreakReason(reason);
            demand.setDepositRefundSummary(formatRefundSummary(refundTx));
            demandRepository.save(demand);
            recommendResultRepository.deleteByDemandId(demand.getId());

            log.warn("需求ID {} 对场地「{}」的锁定自行破裂：{}，押金已全额退回",
                    demand.getId(), venue.getName(), reason);
            if (summary.length() > 0) {
                summary.append("；");
            }
            summary.append(String.format("需求「%s」(ID:%d) 锁定破裂：%s", demand.getDemandName(), demand.getId(), reason));
        }
        return summary.length() > 0 ? summary.toString() : null;
    }

    private String buildBreakReason(Venue venue, ActivityDemand demand,
                                    Integer newStatus, BigDecimal newPrice, String newFacilities) {
        java.util.List<String> reasons = new java.util.ArrayList<>();

        if (newStatus != null && newStatus != 1) {
            reasons.add("场地已被管理员停用");
        }
        if (newPrice != null && demand.getBudgetMax() != null
                && newPrice.compareTo(demand.getBudgetMax()) > 0) {
            reasons.add(String.format("日租金调整为¥%s，已超过需求预算上限¥%s",
                    newPrice.stripTrailingZeros().toPlainString(),
                    demand.getBudgetMax().stripTrailingZeros().toPlainString()));
        }
        if (newFacilities != null && demand.getRequiredFacilities() != null
                && !demand.getRequiredFacilities().trim().isEmpty()) {
            List<String> required = Arrays.stream(demand.getRequiredFacilities().split(","))
                    .map(String::trim).filter(s -> !s.isEmpty()).collect(Collectors.toList());
            List<String> available = Arrays.stream(newFacilities.split(","))
                    .map(String::trim).filter(s -> !s.isEmpty()).collect(Collectors.toList());
            List<String> removed = required.stream().filter(f -> !available.contains(f)).collect(Collectors.toList());
            if (!removed.isEmpty()) {
                reasons.add("需求必备设施被拆除：" + String.join("、", removed));
            }
        }

        return reasons.isEmpty() ? null : String.join("；", reasons);
    }

    private LockRecord closeActiveRecord(Long demandId, String status, String reason) {
        List<LockRecord> records = lockRecordRepository.findByDemandIdOrderByLockedAtDesc(demandId);
        LockRecord active = records.stream()
                .filter(r -> "LOCKED".equals(r.getStatus()))
                .findFirst()
                .orElse(null);
        if (active != null) {
            active.setStatus(status);
            active.setBreakReason(reason);
            active.setReleasedAt(LocalDateTime.now());
            return lockRecordRepository.save(active);
        }
        return null;
    }

    private void clearLockFields(ActivityDemand demand) {
        demand.setLocked(0);
        demand.setLockedVenueId(null);
        demand.setLockedVenueName(null);
        demand.setLockedAt(null);
        demand.setOpened(0);
        demand.setOpenedAt(null);
        demand.setDepositAmount(null);
        demand.setDepositFreezeId(null);
    }

    private String formatRefundSummary(DepositTransaction tx) {
        if (tx == null) {
            return null;
        }
        String rate = tx.getRefundRate() == null
                ? "100" : tx.getRefundRate().stripTrailingZeros().toPlainString();
        return String.format("冻结押金¥%s，按%s%%退回¥%s，没收¥%s",
                tx.getAmount().stripTrailingZeros().toPlainString(),
                rate,
                tx.getRefundAmount().stripTrailingZeros().toPlainString(),
                tx.getForfeitAmount().stripTrailingZeros().toPlainString());
    }
}
