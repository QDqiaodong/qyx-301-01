package com.example.salon.service;

import com.example.salon.entity.ActivityDemand;
import com.example.salon.entity.LockRecord;
import com.example.salon.entity.SiteVisit;
import com.example.salon.entity.Venue;
import com.example.salon.repository.ActivityDemandRepository;
import com.example.salon.repository.LockRecordRepository;
import com.example.salon.repository.SiteVisitRepository;
import com.example.salon.repository.VenueRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * 踩点试场登记。
 * 规则：每条需求至多一档；必须写清场地、日期、上午/下午、预留人数；
 * 若该场地当天已有正式活动确认锁定，登记失败并点明被哪条需求挡住。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SiteVisitService {

    private final SiteVisitRepository siteVisitRepository;
    private final ActivityDemandRepository demandRepository;
    private final VenueRepository venueRepository;
    private final LockRecordRepository lockRecordRepository;

    @Transactional
    public SiteVisit register(Long demandId, SiteVisit request) {
        ActivityDemand demand = demandRepository.findById(demandId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "需求不存在"));

        if (request.getVenueId() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请选择试场场地");
        }
        if (request.getVisitDate() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请选择试场日期");
        }
        String slot = normalizeSlot(request.getTimeSlot());
        Integer reservedPeople = request.getReservedPeople();
        if (reservedPeople == null || reservedPeople <= 0) {
            reservedPeople = demand.getExpectedPeople();
        }
        if (reservedPeople == null || reservedPeople <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "试场预留人数必须大于0");
        }

        Venue venue = venueRepository.findById(request.getVenueId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "试场场地不存在"));
        if (venue.getStatus() == null || venue.getStatus() != 1) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "该场地已停用，不能登记试场");
        }

        // 该场地当天已经有人把正式活动确认锁定 -> 试场登记失败，点明被哪条已锁定需求挡住
        List<LockRecord> blockers = lockRecordRepository.findByVenueIdAndStatus(venue.getId(), "LOCKED");
        LockRecord blocker = blockers.stream()
                .filter(rec -> !Objects.equals(rec.getDemandId(), demandId))
                .filter(rec -> sameDay(rec, request.getVisitDate()))
                .findFirst()
                .orElse(null);
        if (blocker != null) {
            String message = String.format(
                    "试场登记失败：场地「%s」在 %s 已被需求「%s」（需求ID：%d）确认锁定正式活动",
                    venue.getName(), request.getVisitDate(),
                    blocker.getDemandName() == null ? "" : blocker.getDemandName(),
                    blocker.getDemandId());
            throw new ResponseStatusException(HttpStatus.CONFLICT, message);
        }

        // 一条需求一档：重复登记按覆盖更新处理
        SiteVisit visit = siteVisitRepository.findByDemandId(demandId).orElseGet(() -> {
            SiteVisit v = new SiteVisit();
            v.setDemandId(demandId);
            return v;
        });
        visit.setVenueId(venue.getId());
        visit.setVenueName(venue.getName());
        visit.setVisitDate(request.getVisitDate());
        visit.setTimeSlot(slot);
        visit.setReservedPeople(reservedPeople);

        log.info("需求ID {} 登记试场：场地「{}」{} {}", demandId, venue.getName(), request.getVisitDate(), slotText(slot));
        return siteVisitRepository.save(visit);
    }

    @Transactional(readOnly = true)
    public SiteVisit getByDemand(Long demandId) {
        return siteVisitRepository.findByDemandId(demandId).orElse(null);
    }

    @Transactional
    public void cancel(Long demandId) {
        siteVisitRepository.findByDemandId(demandId).ifPresent(siteVisitRepository::delete);
    }

    /**
     * 判断某条有效锁定记录的期望日期是否与试场日期为同一天。
     */
    private boolean sameDay(LockRecord record, LocalDate visitDate) {
        return demandRepository.findById(record.getDemandId())
                .filter(d -> d.getLocked() != null && d.getLocked() == 1)
                .map(ActivityDemand::getExpectedDate)
                .filter(Objects::nonNull)
                .map(date -> date.toLocalDate().equals(visitDate))
                .orElse(false);
    }

    private String normalizeSlot(String slot) {
        if (slot == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "试场时段只能选上午或下午");
        }
        String trimmed = slot.trim();
        if ("MORNING".equals(trimmed) || "上午".equals(trimmed)) {
            return "MORNING";
        }
        if ("AFTERNOON".equals(trimmed) || "下午".equals(trimmed)) {
            return "AFTERNOON";
        }
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "试场时段只能选上午或下午");
    }

    private String slotText(String slot) {
        return "MORNING".equals(slot) ? "上午" : "下午";
    }
}
