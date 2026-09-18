package com.example.salon.service;

import com.example.salon.dto.VenueSaveResponse;
import com.example.salon.entity.Venue;
import com.example.salon.repository.ActivityDemandRepository;
import com.example.salon.repository.DepositTransactionRepository;
import com.example.salon.repository.DutySigninRepository;
import com.example.salon.repository.InvoiceRepository;
import com.example.salon.repository.LockRecordRepository;
import com.example.salon.repository.OpeningRecordRepository;
import com.example.salon.repository.RecommendResultRepository;
import com.example.salon.repository.SiteVisitRepository;
import com.example.salon.repository.VenueRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * 场地资料维护。场地对外改名时，名称在同一份事务里同步到所有引用该场地的历史/流水/卡片：
 * 锁定历史、押金流水、推荐结果（以及仍锁定该场地的需求、踩点登记、值守签到、开场条）。
 * 同步只改场地名一列——冻结流水的金额、客户、退回比例全部保留，
 * 绝不为对齐名称把旧冻结作废重冻，也不允许流水继续挂旧名。
 * 两人前后脚改同一块场地：乐观锁只放行最后一次保存，持旧版本者收到 409，
 * 并看到对方留下的最新名称。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class VenueService {

    private final VenueRepository venueRepository;
    private final LockService lockService;
    private final LockRecordRepository lockRecordRepository;
    private final DepositTransactionRepository depositTransactionRepository;
    private final RecommendResultRepository recommendResultRepository;
    private final ActivityDemandRepository activityDemandRepository;
    private final SiteVisitRepository siteVisitRepository;
    private final DutySigninRepository dutySigninRepository;
    private final OpeningRecordRepository openingRecordRepository;
    private final InvoiceRepository invoiceRepository;

    /**
     * 更新场地。停用、日租金抬过锁定需求预算上限、拆掉必备设施时，相关锁定自行破裂；
     * 改名时把新名同步到锁定历史、押金流水、推荐结果等全部引用处。
     */
    @Transactional
    public VenueSaveResponse updateVenue(Long id, Venue input) {
        Venue existing = venueRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "场地不存在"));

        Integer newStatus = input.getStatus() == null ? existing.getStatus() : input.getStatus();
        // 停用、日租金抬过锁定需求预算上限、拆掉还在要的必备设施时，锁定自行破裂
        String breakMessage = lockService.breakLocksForVenueChange(
                existing, newStatus, input.getPricePerDay(), input.getFacilities());

        String oldName = existing.getName();
        String newName = input.getName();

        existing.setName(newName);
        existing.setCapacity(input.getCapacity());
        existing.setPricePerDay(input.getPricePerDay());
        existing.setFacilities(input.getFacilities());
        existing.setActivityTypes(input.getActivityTypes());
        existing.setDescription(input.getDescription());
        existing.setStatus(newStatus);

        Venue saved = saveWithConcurrencyGuard(existing);

        // 场地改名：新名与场地本身同事务落库，三处（锁定历史、押金流水、推荐结果）
        // 以及其它引用快照一起改成现名，关页再开看到的还是同一份现名。
        if (newName != null && !newName.equals(oldName)) {
            propagateVenueName(id, newName);
            log.info("场地ID {} 改名「{}」→「{}」，已同步锁定历史、押金流水、推荐结果等全部引用",
                    id, oldName, newName);
        }

        return VenueSaveResponse.builder()
                .venue(saved)
                .breakMessage(breakMessage)
                .build();
    }

    /** 停用（删除）场地：同样触发锁定破裂。 */
    @Transactional
    public VenueSaveResponse deactivateVenue(Long id) {
        Venue venue = venueRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "场地不存在"));
        String breakMessage = lockService.breakLocksForVenueChange(
                venue, 0, venue.getPricePerDay(), venue.getFacilities());
        venue.setStatus(0);
        Venue saved = saveWithConcurrencyGuard(venue);
        return VenueSaveResponse.builder()
                .venue(saved)
                .breakMessage(breakMessage)
                .build();
    }

    private Venue saveWithConcurrencyGuard(Venue venue) {
        try {
            // 立刻 flush：若两人前后脚改名，持旧版本的一方在这里就拿到乐观锁冲突，
            // 不用等事务提交，错误信息里带上对方已保存的最新名。
            return venueRepository.saveAndFlush(venue);
        } catch (OptimisticLockingFailureException e) {
            String latestName = venueRepository.findById(venue.getId())
                    .map(Venue::getName).orElse("（该场地）");
            throw new ResponseStatusException(HttpStatus.CONFLICT, String.format(
                    "场地信息已被其他人更新：当前对外名称已改为「%s」，您本次改名未保存。"
                            + "请重新打开编辑框、按最新名称修改后再提交。", latestName));
        }
    }

    /**
     * 把新场地名同步到所有按 venue_id 引用该场地的行。
     * 每条批量语句只更新场地名一列：
     * 押金流水的金额、客户、比例、时间原样保留（旧冻结不作废、不重冻）；
     * 锁定状态、推荐评分、值守/开场状态一律不动。
     */
    private void propagateVenueName(Long venueId, String newName) {
        lockRecordRepository.updateVenueNameByVenueId(venueId, newName);
        depositTransactionRepository.updateVenueNameByVenueId(venueId, newName);
        recommendResultRepository.updateVenueNameByVenueId(venueId, newName);
        activityDemandRepository.updateLockedVenueNameByVenueId(venueId, newName);
        siteVisitRepository.updateVenueNameByVenueId(venueId, newName);
        dutySigninRepository.updateVenueNameByVenueId(venueId, newName);
        openingRecordRepository.updateVenueNameByVenueId(venueId, newName);
        invoiceRepository.updateVenueNameByVenueId(venueId, newName);
    }
}
