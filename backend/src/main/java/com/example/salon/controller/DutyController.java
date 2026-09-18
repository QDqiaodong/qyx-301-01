package com.example.salon.controller;

import com.example.salon.dto.DayDutyView;
import com.example.salon.dto.DutyActionRequest;
import com.example.salon.entity.DutySignin;
import com.example.salon.entity.OpeningRecord;
import com.example.salon.service.DutyService;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;

/**
 * 开场当天值守：两岗签到是开场条件（安保规定）。
 * 主值守、机动都签到齐全，场地才能从已锁定变成可开场；
 * 机动空着不能开场，也不能绕过签到直接改状态。
 */
@RestController
@RequestMapping("/api/duty")
@RequiredArgsConstructor
@CrossOrigin(origins = "*")
public class DutyController {

    private final DutyService dutyService;

    /** 当天页：默认今天；?date=2026-09-18 查看指定日期的两岗与开场状态 */
    @GetMapping("/day")
    public ResponseEntity<List<DayDutyView>> getDayView(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return ResponseEntity.ok(dutyService.getDayView(date));
    }

    /** 单个需求的值守视图（与当天页同一份数据） */
    @GetMapping("/demand/{demandId}")
    public ResponseEntity<DayDutyView> getDemandDuty(@PathVariable Long demandId) {
        return ResponseEntity.ok(dutyService.getDemandDuty(demandId));
    }

    /** 岗位签到：同一岗只能一人在岗，已被占用时返回 409 并提示被谁占用 */
    @PostMapping("/signin")
    public ResponseEntity<DutySignin> signIn(@RequestBody DutyActionRequest request) {
        return ResponseEntity.ok(
                dutyService.signIn(request.getDemandId(), request.getPost(), request.getStaffName()));
    }

    /** 中途撤岗：已开场的退回已锁定、当天开场条作废；押金冻结不动 */
    @PostMapping("/withdraw")
    public ResponseEntity<DutySignin> withdraw(@RequestBody DutyActionRequest request) {
        return ResponseEntity.ok(dutyService.withdraw(request.getDemandId(), request.getPost()));
    }

    /** 开场：两岗签到齐全才能把已锁定变成已开场，生成当天开场条 */
    @PostMapping("/open")
    public ResponseEntity<OpeningRecord> openVenue(@RequestBody DutyActionRequest request) {
        return ResponseEntity.ok(dutyService.openVenue(request.getDemandId()));
    }
}
