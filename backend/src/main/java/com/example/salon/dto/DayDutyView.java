package com.example.salon.dto;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 当天值守视图：一块场地当天的两岗签到与开场状态。
 * 前端当天页、签到/撤岗/开场后的刷新都读这一份持久化数据，
 * 保证「再进当天页，两岗和能不能开场还是同一份」。
 *
 * @param primaryPost 主值守在岗信息；null 表示该岗空着（未签到或已撤岗）
 * @param flexPost    机动岗在岗信息；null 表示该岗空着
 * @param bothSigned  两岗是否都已签到
 * @param canOpen     当前能否开场（已锁定 + 两岗齐全 + 未开场）
 * @param opened      是否已开场
 */
public record DayDutyView(
        Long demandId,
        String demandName,
        String customerName,
        Long venueId,
        String venueName,
        LocalDate activityDate,
        PostView primaryPost,
        PostView flexPost,
        boolean bothSigned,
        boolean canOpen,
        boolean opened,
        Long openingId,
        LocalDateTime openedAt
) {

    /** 在岗信息：签到人 + 签到时间 */
    public record PostView(String staffName, LocalDateTime signedAt) {
    }
}
