package com.example.salon.service;

import com.example.salon.dto.RecommendResponse;
import com.example.salon.entity.ActivityDemand;
import com.example.salon.entity.RecommendResult;
import com.example.salon.entity.SiteVisit;
import com.example.salon.entity.Venue;
import com.example.salon.repository.ActivityDemandRepository;
import com.example.salon.repository.RecommendResultRepository;
import com.example.salon.repository.SiteVisitRepository;
import com.example.salon.repository.VenueRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class RecommendService {

    private final VenueRepository venueRepository;
    private final ActivityDemandRepository demandRepository;
    private final RecommendResultRepository recommendResultRepository;
    private final SiteVisitRepository siteVisitRepository;
    private final WeightConfigService weightConfigService;

    @Transactional
    public RecommendResponse calculateRecommend(Long demandId) {
        Optional<ActivityDemand> demandOpt = demandRepository.findById(demandId);
        if (demandOpt.isEmpty()) {
            return RecommendResponse.builder()
                    .results(Collections.emptyList())
                    .matchStatus(2)
                    .warningMessage("需求不存在")
                    .missingResources(Collections.emptyList())
                    .build();
        }

        ActivityDemand demand = demandOpt.get();

        // 锁定期间名单冻结，不能重算；要改数字须先解除锁定并作废当前推荐
        if (demand.getLocked() != null && demand.getLocked() == 1) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "该需求已锁定场地「" + demand.getLockedVenueName() + "」，请先解除锁定再重新计算推荐");
        }

        // 活动已取消并完成退押结算，属终态，不能再重算推荐重新占场
        if (demand.getMatchStatus() != null && demand.getMatchStatus() == 6) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "该需求的活动已取消并完成退押，如需继续办活动请重新提交需求");
        }

        LocalDate expectedDay = demand.getExpectedDate() == null ? null : demand.getExpectedDate().toLocalDate();
        // 同一场地同一天已登记的踩点试场预留人数（多档试场合计占用）
        Map<Long, Integer> reservedByVenue = sumReservedPeople(expectedDay);

        List<Venue> venues = venueRepository.findByStatus(1);

        if (venues.isEmpty()) {
            demand.setMatchStatus(2);
            demand.setLockBreakReason(null);
            demandRepository.save(demand);
            return RecommendResponse.builder()
                    .results(Collections.emptyList())
                    .matchStatus(2)
                    .warningMessage("当前无可用场地，请联系管理员添加场地")
                    .missingResources(Collections.singletonList("无可用场地"))
                    .build();
        }

        List<RecommendResult> allResults = venues.stream()
                .map(venue -> calculateMatchScore(venue, demand,
                        reservedByVenue.getOrDefault(venue.getId(), 0)))
                .sorted(Comparator.comparing(RecommendResult::getMatchScore).reversed())
                .collect(Collectors.toList());

        List<RecommendResult> validResults = allResults.stream()
                .filter(result -> result.getMatchScore().compareTo(weightConfigService.getWeight("threshold.minScore")) >= 0)
                .collect(Collectors.toList());

        recommendResultRepository.deleteByDemandId(demandId);

        for (int i = 0; i < validResults.size(); i++) {
            validResults.get(i).setRecommendOrder(i + 1);
            recommendResultRepository.save(validResults.get(i));
        }

        List<String> missingResources = analyzeMissingResources(demand, venues, allResults, reservedByVenue);
        String warningMessage = generateWarningMessage(demand, venues, validResults, missingResources);

        int matchStatus = validResults.isEmpty() ? 2 : (missingResources.isEmpty() ? 1 : 3);
        demand.setMatchStatus(matchStatus);
        // 重新算出有效名单后，历史破裂原因不再作为当前结果的提示
        demand.setLockBreakReason(null);
        demandRepository.save(demand);

        log.info("需求ID {} 推荐计算完成，共 {} 个匹配场地", demandId, validResults.size());
        return RecommendResponse.builder()
                .results(validResults)
                .matchStatus(matchStatus)
                .warningMessage(warningMessage)
                .missingResources(missingResources)
                .build();
    }

    /**
     * 汇总某天各场地被踩点试场预留的人数。
     * 试场只占半天，但两场试场无论上午/下午都按预留人数合计折算，
     * 不能把整场当成空场给正式活动打分。
     */
    private Map<Long, Integer> sumReservedPeople(LocalDate day) {
        Map<Long, Integer> reservedByVenue = new HashMap<>();
        if (day == null) {
            return reservedByVenue;
        }
        List<SiteVisit> visits = siteVisitRepository.findAll().stream()
                .filter(v -> day.equals(v.getVisitDate()))
                .collect(Collectors.toList());
        for (SiteVisit visit : visits) {
            reservedByVenue.merge(visit.getVenueId(), visit.getReservedPeople(), Integer::sum);
        }
        return reservedByVenue;
    }

    private List<String> analyzeMissingResources(ActivityDemand demand, List<Venue> venues,
                                                 List<RecommendResult> allResults,
                                                 Map<Long, Integer> reservedByVenue) {
        List<String> missingResources = new ArrayList<>();

        boolean capacityMatch = venues.stream()
                .anyMatch(v -> effectiveCapacity(v, reservedByVenue.getOrDefault(v.getId(), 0))
                        >= demand.getExpectedPeople());
        if (!capacityMatch) {
            int maxCapacity = venues.stream()
                    .mapToInt(v -> effectiveCapacity(v, reservedByVenue.getOrDefault(v.getId(), 0)))
                    .max().orElse(0);
            missingResources.add(String.format("场地容量不足（需求%d人，试场折算后最大可排容量%d人）",
                    demand.getExpectedPeople(), maxCapacity));
        }

        if (demand.getRequiredFacilities() != null && !demand.getRequiredFacilities().isEmpty()) {
            List<String> requiredFacilities = Arrays.asList(demand.getRequiredFacilities().split(","))
                    .stream().map(String::trim).filter(s -> !s.isEmpty()).collect(Collectors.toList());
            
            Set<String> allAvailableFacilities = venues.stream()
                    .map(Venue::getFacilities)
                    .filter(Objects::nonNull)
                    .flatMap(f -> Arrays.stream(f.split(",")))
                    .map(String::trim)
                    .filter(s -> !s.isEmpty())
                    .collect(Collectors.toSet());

            List<String> unavailableFacilities = requiredFacilities.stream()
                    .filter(f -> !allAvailableFacilities.contains(f))
                    .collect(Collectors.toList());

            if (!unavailableFacilities.isEmpty()) {
                missingResources.add(String.format("缺少必备设施：%s", String.join(", ", unavailableFacilities)));
            }
        }

        boolean activityTypeMatch = venues.stream()
                .anyMatch(v -> {
                    if (v.getActivityTypes() == null) return false;
                    List<String> types = Arrays.asList(v.getActivityTypes().split(","))
                            .stream().map(String::trim).filter(s -> !s.isEmpty()).collect(Collectors.toList());
                    return types.contains(demand.getActivityCategory()) || 
                           types.stream().anyMatch(t -> demand.getActivityCategory().contains(t) || t.contains(demand.getActivityCategory()));
                });
        if (!activityTypeMatch) {
            missingResources.add(String.format("无适配场地支持「%s」活动类型", demand.getActivityCategory()));
        }

        if (demand.getBudgetMax() != null) {
            boolean budgetMatch = venues.stream()
                    .anyMatch(v -> v.getPricePerDay().compareTo(demand.getBudgetMax()) <= 0);
            if (!budgetMatch) {
                BigDecimal minPrice = venues.stream().map(Venue::getPricePerDay).min(BigDecimal::compareTo).orElse(BigDecimal.ZERO);
                missingResources.add(String.format("预算不足（预算上限%d，最低价格%d）", demand.getBudgetMax().intValue(), minPrice.intValue()));
            }
        }

        return missingResources;
    }

    private String generateWarningMessage(ActivityDemand demand, List<Venue> venues, 
                                          List<RecommendResult> validResults, List<String> missingResources) {
        if (validResults.isEmpty()) {
            if (venues.isEmpty()) {
                return "当前无可用场地，请联系管理员";
            }
            return "未找到完全匹配的场地，以下资源存在缺口：" + String.join("；", missingResources);
        }
        if (!missingResources.isEmpty()) {
            return "已找到匹配场地，但存在以下资源缺口：" + String.join("；", missingResources);
        }
        return null;
    }

    private RecommendResult calculateMatchScore(Venue venue, ActivityDemand demand, int reservedPeople) {
        BigDecimal capacityWeight = weightConfigService.getWeight("weight.capacity");
        BigDecimal facilityWeight = weightConfigService.getWeight("weight.facility");
        BigDecimal activityTypeWeight = weightConfigService.getWeight("weight.activityType");
        BigDecimal budgetWeight = weightConfigService.getWeight("weight.budget");

        // 同一场地同一天已有踩点试场：人数得分按场地容量减去试场预留人数来算，不能按整场空着打分
        int effectiveCapacity = effectiveCapacity(venue, reservedPeople);
        BigDecimal capacityScore = calculateCapacityScore(effectiveCapacity, demand.getExpectedPeople());
        BigDecimal facilityScore = calculateFacilityScore(venue.getFacilities(), demand.getRequiredFacilities());
        BigDecimal activityTypeScore = calculateActivityTypeScore(venue.getActivityTypes(), demand.getActivityCategory());
        BigDecimal budgetScore = calculateBudgetScore(venue.getPricePerDay(), demand.getBudgetMin(), demand.getBudgetMax());

        BigDecimal matchScore = capacityScore.multiply(capacityWeight)
                .add(facilityScore.multiply(facilityWeight))
                .add(activityTypeScore.multiply(activityTypeWeight))
                .add(budgetScore.multiply(budgetWeight))
                .setScale(2, RoundingMode.HALF_UP);

        StringBuilder reason = new StringBuilder();
        if (reservedPeople > 0) {
            reason.append(String.format(
                    "当天有踩点试场，还占着%d人，按可排容量%d/%d人折算人数得分;",
                    reservedPeople, effectiveCapacity, venue.getCapacity()));
        }
        if (capacityScore.compareTo(BigDecimal.valueOf(100)) < 0) {
            reason.append("容纳人数略有差异;");
        }
        if (facilityScore.compareTo(BigDecimal.valueOf(100)) < 0) {
            reason.append("部分设施未匹配;");
        }
        if (activityTypeScore.compareTo(BigDecimal.valueOf(100)) < 0) {
            reason.append("活动类型适配度一般;");
        }
        if (budgetScore.compareTo(BigDecimal.valueOf(100)) < 0) {
            reason.append("预算匹配度一般;");
        }

        RecommendResult result = new RecommendResult();
        result.setDemandId(demand.getId());
        result.setVenueId(venue.getId());
        result.setVenueName(venue.getName());
        result.setMatchScore(matchScore);
        result.setCapacityScore(capacityScore);
        result.setFacilityScore(facilityScore);
        result.setActivityTypeScore(activityTypeScore);
        result.setBudgetScore(budgetScore);
        result.setReason(reason.length() > 0 ? reason.toString() : "完全匹配");

        return result;
    }

    /**
     * 试场折算后的当天可排容量，扣完为负时按0处理。
     */
    private int effectiveCapacity(Venue venue, int reservedPeople) {
        return Math.max(0, venue.getCapacity() - reservedPeople);
    }

    private BigDecimal calculateCapacityScore(Integer venueCapacity, Integer expectedPeople) {
        BigDecimal minThreshold = weightConfigService.getWeight("threshold.minCapacity");
        BigDecimal maxThreshold = weightConfigService.getWeight("threshold.maxCapacity");

        BigDecimal capacityRatio = BigDecimal.valueOf(venueCapacity)
                .divide(BigDecimal.valueOf(expectedPeople), 4, RoundingMode.HALF_UP);

        if (capacityRatio.compareTo(minThreshold) >= 0 && capacityRatio.compareTo(maxThreshold) <= 0) {
            return BigDecimal.valueOf(100);
        }

        if (capacityRatio.compareTo(minThreshold) < 0) {
            return capacityRatio.divide(minThreshold, 4, RoundingMode.HALF_UP).multiply(BigDecimal.valueOf(100))
                    .setScale(2, RoundingMode.HALF_UP);
        }

        BigDecimal excessRatio = capacityRatio.subtract(maxThreshold)
                .divide(BigDecimal.valueOf(2), 4, RoundingMode.HALF_UP);
        BigDecimal score = BigDecimal.valueOf(100).subtract(excessRatio.multiply(BigDecimal.valueOf(50)))
                .setScale(2, RoundingMode.HALF_UP);
        return score.compareTo(BigDecimal.ZERO) > 0 ? score : BigDecimal.ZERO;
    }

    private BigDecimal calculateFacilityScore(String venueFacilities, String requiredFacilities) {
        if (requiredFacilities == null || requiredFacilities.trim().isEmpty()) {
            return BigDecimal.valueOf(100);
        }

        if (venueFacilities == null || venueFacilities.trim().isEmpty()) {
            return BigDecimal.ZERO;
        }

        List<String> requiredList = Arrays.asList(requiredFacilities.split(","))
                .stream().map(String::trim).filter(s -> !s.isEmpty()).collect(Collectors.toList());
        List<String> venueList = Arrays.asList(venueFacilities.split(","))
                .stream().map(String::trim).filter(s -> !s.isEmpty()).collect(Collectors.toList());

        long matched = requiredList.stream().filter(venueList::contains).count();
        BigDecimal score = BigDecimal.valueOf(matched)
                .divide(BigDecimal.valueOf(requiredList.size()), 4, RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(100))
                .setScale(2, RoundingMode.HALF_UP);

        return score;
    }

    private BigDecimal calculateActivityTypeScore(String venueActivityTypes, String demandActivityCategory) {
        if (demandActivityCategory == null || demandActivityCategory.trim().isEmpty()) {
            return BigDecimal.valueOf(100);
        }

        if (venueActivityTypes == null || venueActivityTypes.trim().isEmpty()) {
            return BigDecimal.ZERO;
        }

        List<String> venueTypes = Arrays.asList(venueActivityTypes.split(","))
                .stream().map(String::trim).filter(s -> !s.isEmpty()).collect(Collectors.toList());

        if (venueTypes.contains(demandActivityCategory)) {
            return BigDecimal.valueOf(100);
        }

        for (String type : venueTypes) {
            if (demandActivityCategory.contains(type) || type.contains(demandActivityCategory)) {
                return BigDecimal.valueOf(75);
            }
        }

        return BigDecimal.valueOf(50);
    }

    private BigDecimal calculateBudgetScore(BigDecimal venuePrice, BigDecimal budgetMin, BigDecimal budgetMax) {
        if (budgetMin == null && budgetMax == null) {
            return BigDecimal.valueOf(100);
        }

        if (venuePrice == null) {
            return BigDecimal.ZERO;
        }

        boolean inRange = true;
        if (budgetMin != null && venuePrice.compareTo(budgetMin) < 0) {
            inRange = false;
        }
        if (budgetMax != null && venuePrice.compareTo(budgetMax) > 0) {
            inRange = false;
        }

        if (inRange) {
            return BigDecimal.valueOf(100);
        }

        if (budgetMax != null && venuePrice.compareTo(budgetMax) > 0) {
            BigDecimal overBudgetRatio = venuePrice.subtract(budgetMax)
                    .divide(budgetMax, 4, RoundingMode.HALF_UP);
            BigDecimal score = BigDecimal.valueOf(100).subtract(overBudgetRatio.multiply(BigDecimal.valueOf(100)))
                    .setScale(2, RoundingMode.HALF_UP);
            return score.compareTo(BigDecimal.ZERO) > 0 ? score : BigDecimal.ZERO;
        }

        return BigDecimal.valueOf(80);
    }

    public List<RecommendResult> getRecommendResults(Long demandId) {
        return recommendResultRepository.findByDemandIdOrderByRecommendOrder(demandId);
    }
}
