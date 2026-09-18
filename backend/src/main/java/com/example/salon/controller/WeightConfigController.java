package com.example.salon.controller;

import com.example.salon.service.WeightConfigService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.Map;

@RestController
@RequestMapping("/api/weight")
@RequiredArgsConstructor
@CrossOrigin(origins = "*")
public class WeightConfigController {

    private final WeightConfigService weightConfigService;

    @GetMapping
    public ResponseEntity<Map<String, BigDecimal>> getAllWeights() {
        return ResponseEntity.ok(weightConfigService.getAllWeights());
    }

    @PutMapping("/{key}")
    public ResponseEntity<BigDecimal> updateWeight(@PathVariable String key, @RequestParam BigDecimal value) {
        return ResponseEntity.ok(weightConfigService.updateWeight(key, value));
    }

    @PostMapping("/reload")
    public ResponseEntity<Void> reloadWeights() {
        weightConfigService.loadConfigsToRedis();
        return ResponseEntity.ok().build();
    }
}
