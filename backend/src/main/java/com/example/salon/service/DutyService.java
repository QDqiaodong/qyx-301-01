package com.example.salon.service;

import com.example.salon.dto.DayDutyView;
import com.example.salon.entity.ActivityDemand;
import com.example.salon.entity.DutySignin;
import com.example.salon.entity.LockRecord;
import com.example.salon.entity.OpeningRecord;
import com.example.salon.repository.ActivityDemandRepository;
import com.example.salon.repository.DutySigninRepository;
import com.example.salon.repository.LockRecordRepository;
import com.example.salon.repository.OpeningRecordRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 开场当天值守：每块场地当天有主值守、机动两个岗，两岗都签到齐全，
 * 场地才能从已锁定变成可开场（开场按钮才生效）。
 * 安保规矩：机动空着就不能开场，也不能为了赶场绕过签到把已锁定直接改成可开场。
 * 主值守/机动中途撤岗导致两岗不齐时，已开场的退回已锁定、当天开场条作废；
 * 撤岗只动值守与开场状态，押金冻结保持不变（不结算、不退押、不冲掉冻结）。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class DutyService {

    private final ActivityDemandRepository demandRepository;
    private final LockRecordRepository lockRecordRepository;
    private final DutySigninRepository dutySigninRepository;
    private final OpeningRecordRepository openingRecordRepository;

    // ==================== 签到 ====================

    /**
     * 岗位签到。同一锁定同一岗只认一个在岗人：
     * 已有人在岗 → 409 提示该岗已被占用；
     * 两人前后脚同时签 → 数据库唯一约束只放行一个，失败方同样看到该岗已被占用。
     * 撤岗后的空岗可以重新签到。
     */
    @Transactional
    public DutySignin signIn(Long demandId, String post, String staffName) {
        ActivityDemand demand = requireLockedDemand(demandId);
        String normalizedPost = normalizePost(post);
        String name = requireStaffName(staffName);
        LockRecord lock = requireActiveLockForUpdate(demandId);

        DutySignin existing = dutySigninRepository
                .findByLockRecordIdAndPost(lock.getId(), normalizedPost).orElse(null);
        if (existing != null && DutySignin.STATUS_SIGNED.equals(existing.getStatus())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, String.format(
                    "%s已被「%s」占用：同一岗位当天只能一人在岗，请先由在岗人撤岗",
                    postLabel(normalizedPost), existing.getStaffName()));
        }

        if (existing != null) {
            // 撤岗后的空岗：复用原行重新签到
            existing.setStaffName(name);
            existing.setStatus(DutySignin.STATUS_SIGNED);
            existing.setSignedAt(LocalDateTime.now());
            existing.setWithdrawnAt(null);
            DutySignin saved = dutySigninRepository.save(existing);
            log.info("需求ID {} {}重新签到：{}", demandId, postLabel(normalizedPost), name);
            return saved;
        }

        DutySignin signin = new DutySignin();
        signin.setDemandId(demand.getId());
        signin.setDemandName(demand.getDemandName());
        signin.setLockRecordId(lock.getId());
        signin.setVenueId(demand.getLockedVenueId());
        signin.setVenueName(demand.getLockedVenueName());
        signin.setActivityDate(activityDateOf(demand));
        signin.setPost(normalizedPost);
        signin.setStaffName(name);
        signin.setStatus(DutySignin.STATUS_SIGNED);
        signin.setSignedAt(LocalDateTime.now());
        try {
            DutySignin saved = dutySigninRepository.save(signin);
            log.info("需求ID {} {}签到：{}", demandId, postLabel(normalizedPost), name);
            return saved;
        } catch (DataIntegrityViolationException e) {
            // 两人前后脚签同一岗：唯一约束只放行一个，失败方看到该岗已被占用
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    postLabel(normalizedPost) + "已被占用：同一岗位当天只能一人在岗");
        }
    }

    // ==================== 撤岗 ====================

    /**
     * 中途撤岗：该岗空出。若场地已开场，两岗不齐必须退回已锁定、当天开场条作废；
     * 撤岗只动值守与开场状态，押金冻结保持不变（不调押金结算、不产生退押流水）。
     */
    @Transactional
    public DutySignin withdraw(Long demandId, String post) {
        ActivityDemand demand = requireLockedDemand(demandId);
        String normalizedPost = normalizePost(post);
        LockRecord lock = requireActiveLockForUpdate(demandId);

        DutySignin signin = dutySigninRepository
                .findByLockRecordIdAndPost(lock.getId(), normalizedPost)
                .filter(s -> DutySignin.STATUS_SIGNED.equals(s.getStatus()))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        postLabel(normalizedPost) + "当前无在岗人员，无法撤岗"));

        signin.setStatus(DutySignin.STATUS_WITHDRAWN);
        signin.setWithdrawnAt(LocalDateTime.now());
        DutySignin saved = dutySigninRepository.save(signin);

        if (demand.getOpened() != null && demand.getOpened() == 1) {
            // 已开场但两岗不再齐：退回已锁定，当天开场条作废；押金冻结不动
            voidOpening(lock.getId(), String.format(
                    "%s「%s」中途撤岗，两岗不齐，已开场退回已锁定，当天开场条作废（押金冻结不变）",
                    postLabel(normalizedPost), signin.getStaffName()));
            demand.setOpened(0);
            demand.setOpenedAt(null);
            demandRepository.save(demand);
            log.warn("需求ID {} {}撤岗，已开场退回已锁定，当天开场条作废（押金冻结不变）",
                    demandId, postLabel(normalizedPost));
        } else {
            log.info("需求ID {} {}撤岗：{}", demandId, postLabel(normalizedPost), signin.getStaffName());
        }
        return saved;
    }

    // ==================== 开场 ====================

    /**
     * 开场：两岗签到齐全才能把已锁定变成已开场，并生成当天开场条。
     * 只签主值守、机动空着 → 409，场地停在已锁定，不能开场。
     */
    @Transactional
    public OpeningRecord openVenue(Long demandId) {
        ActivityDemand demand = requireLockedDemand(demandId);
        if (demand.getOpened() != null && demand.getOpened() == 1) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "该场地当天已开场，请勿重复开场");
        }
        LockRecord lock = requireActiveLockForUpdate(demandId);

        List<DutySignin> signins = dutySigninRepository.findByLockRecordId(lock.getId());
        boolean primarySigned = signedOf(signins, DutySignin.POST_PRIMARY) != null;
        boolean flexSigned = signedOf(signins, DutySignin.POST_FLEX) != null;
        if (!primarySigned || !flexSigned) {
            String missing = !primarySigned && !flexSigned
                    ? "主值守、机动两岗都未签到"
                    : !primarySigned ? "主值守未签到" : "机动岗未签到";
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    missing + "：按安保规定，主值守、机动两岗都签到齐全才能开场；"
                            + "只签一岗不能开场，也不能把已锁定直接改成可开场");
        }

        OpeningRecord opening = new OpeningRecord();
        opening.setDemandId(demand.getId());
        opening.setDemandName(demand.getDemandName());
        opening.setLockRecordId(lock.getId());
        opening.setVenueId(demand.getLockedVenueId());
        opening.setVenueName(demand.getLockedVenueName());
        opening.setActivityDate(activityDateOf(demand));
        opening.setStatus(OpeningRecord.STATUS_OPEN);
        opening.setActiveFlag(1);
        opening.setOpenedAt(LocalDateTime.now());
        OpeningRecord saved;
        try {
            saved = openingRecordRepository.save(opening);
        } catch (DataIntegrityViolationException e) {
            // 并发重复开场：一次锁定最多一条有效开场条
            throw new ResponseStatusException(HttpStatus.CONFLICT, "该场地当天已开场，请勿重复开场");
        }

        demand.setOpened(1);
        demand.setOpenedAt(saved.getOpenedAt());
        demandRepository.save(demand);

        log.info("需求ID {} 两岗签到齐全，场地「{}」开场，当天开场条ID {}",
                demandId, demand.getLockedVenueName(), saved.getId());
        return saved;
    }

    // ==================== 当天页 ====================

    /**
     * 当天页：该日期所有已锁定场地的两岗签到与开场状态。
     * 数据全部来自持久化的签到行与开场条，再进当天页看到的还是同一份。
     */
    @Transactional(readOnly = true)
    public List<DayDutyView> getDayView(LocalDate date) {
        LocalDate day = date == null ? LocalDate.now() : date;
        return demandRepository.findByLocked(1).stream()
                .filter(d -> d.getExpectedDate() != null && d.getExpectedDate().toLocalDate().equals(day))
                .map(this::toDayView)
                .collect(Collectors.toList());
    }

    /** 单个需求的值守视图（与当天页同一份数据，供操作后刷新）。 */
    @Transactional(readOnly = true)
    public DayDutyView getDemandDuty(Long demandId) {
        ActivityDemand demand = demandRepository.findById(demandId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "需求不存在"));
        return toDayView(demand);
    }

    private DayDutyView toDayView(ActivityDemand demand) {
        LockRecord lock = activeLock(demand.getId());
        List<DutySignin> signins = lock == null
                ? List.of() : dutySigninRepository.findByLockRecordId(lock.getId());
        DutySignin primary = signedOf(signins, DutySignin.POST_PRIMARY);
        DutySignin flex = signedOf(signins, DutySignin.POST_FLEX);
        boolean opened = demand.getOpened() != null && demand.getOpened() == 1;
        boolean locked = demand.getLocked() != null && demand.getLocked() == 1;
        boolean bothSigned = primary != null && flex != null;
        OpeningRecord opening = lock == null ? null
                : openingRecordRepository.findByLockRecordIdAndStatus(lock.getId(), OpeningRecord.STATUS_OPEN)
                        .orElse(null);
        return new DayDutyView(
                demand.getId(),
                demand.getDemandName(),
                demand.getCustomerName(),
                demand.getLockedVenueId(),
                demand.getLockedVenueName(),
                demand.getExpectedDate() == null ? null : demand.getExpectedDate().toLocalDate(),
                toPostView(primary),
                toPostView(flex),
                bothSigned,
                locked && bothSigned && !opened,
                opened,
                opening == null ? null : opening.getId(),
                demand.getOpenedAt());
    }

    // ==================== 内部 ====================

    private ActivityDemand requireLockedDemand(Long demandId) {
        if (demandId == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "缺少需求ID");
        }
        ActivityDemand demand = demandRepository.findById(demandId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "需求不存在"));
        if (demand.getLocked() == null || demand.getLocked() != 1) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "该需求当前未锁定场地，不能进行值守签到、撤岗或开场");
        }
        return demand;
    }

    private LockRecord activeLock(Long demandId) {
        return lockRecordRepository.findByDemandIdOrderByLockedAtDesc(demandId).stream()
                .filter(r -> "LOCKED".equals(r.getStatus()))
                .findFirst()
                .orElse(null);
    }

    /**
     * 找到本次有效锁定并加行级写锁：同一锁定下的签到/撤岗/开场串行执行，
     * 两人前后脚签同一岗时，后到者看到前者已占岗。
     */
    private LockRecord requireActiveLockForUpdate(Long demandId) {
        LockRecord lock = activeLock(demandId);
        if (lock == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "该需求当前没有有效锁定记录");
        }
        return lockRecordRepository.findByIdForUpdate(lock.getId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "该需求当前没有有效锁定记录"));
    }

    private void voidOpening(Long lockRecordId, String reason) {
        openingRecordRepository.findByLockRecordIdAndStatus(lockRecordId, OpeningRecord.STATUS_OPEN)
                .ifPresent(opening -> {
                    opening.setStatus(OpeningRecord.STATUS_VOID);
                    opening.setActiveFlag(null);
                    opening.setVoidedAt(LocalDateTime.now());
                    opening.setVoidReason(reason);
                    openingRecordRepository.save(opening);
                });
    }

    private DutySignin signedOf(List<DutySignin> signins, String post) {
        return signins.stream()
                .filter(s -> post.equals(s.getPost()) && DutySignin.STATUS_SIGNED.equals(s.getStatus()))
                .findFirst()
                .orElse(null);
    }

    private DayDutyView.PostView toPostView(DutySignin signin) {
        return signin == null ? null : new DayDutyView.PostView(signin.getStaffName(), signin.getSignedAt());
    }

    private LocalDate activityDateOf(ActivityDemand demand) {
        if (demand.getExpectedDate() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "该需求缺少活动日期，无法安排值守");
        }
        return demand.getExpectedDate().toLocalDate();
    }

    private String normalizePost(String post) {
        String normalized = post == null ? "" : post.trim().toUpperCase();
        if (!DutySignin.POST_PRIMARY.equals(normalized) && !DutySignin.POST_FLEX.equals(normalized)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "岗位无效：只能是主值守（PRIMARY）或机动（FLEX）");
        }
        return normalized;
    }

    private String requireStaffName(String staffName) {
        String name = staffName == null ? "" : staffName.trim();
        if (name.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "签到人姓名不能为空");
        }
        return name;
    }

    private String postLabel(String post) {
        return DutySignin.POST_PRIMARY.equals(post) ? "主值守岗" : "机动岗";
    }
}
