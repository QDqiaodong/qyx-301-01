package com.example.salon.dto;

import lombok.Data;

/**
 * 值守操作请求：签到（demandId + post + staffName）、撤岗（demandId + post）、
 * 开场（demandId）。
 */
@Data
public class DutyActionRequest {

    private Long demandId;

    /** PRIMARY-主值守；FLEX-机动（开场时不用传） */
    private String post;

    /** 签到人姓名（签到时必传） */
    private String staffName;
}
