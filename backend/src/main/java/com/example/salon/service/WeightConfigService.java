package com.example.salon.service;

import com.example.salon.entity.WeightConfig;
import com.example.salon.repository.WeightConfigRepository;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Service
@RequiredArgsConstructor
@Slf4j
public class WeightConfigService {

    private static final String WEIGHT_CONFIG_KEY = "salon:weight:config";

    private final WeightConfigRepository weightConfigRepository;
    private final RedisTemplate<String, Object> redisTemplate;
    private HashOperations<String, String, BigDecimal> hashOperations;

    public void initHashOperations() {
        this.hashOperations = redisTemplate.opsForHash();
    }

    @PostConstruct
    public void init() {
        initHashOperations();
        initDefaultConfigs();
        loadConfigsToRedis();
    }

    private void initDefaultConfigs() {
        Map<String, DefaultConfig> defaults = new HashMap<>();
        defaults.put("weight.capacity", new DefaultConfig(new BigDecimal("0.30"), "容纳人数权重"));
        defaults.put("weight.facility", new DefaultConfig(new BigDecimal("0.25"), "配套设施权重"));
        defaults.put("weight.activityType", new DefaultConfig(new BigDecimal("0.25"), "活动类型权重"));
        defaults.put("weight.budget", new DefaultConfig(new BigDecimal("0.20"), "预算匹配权重"));
        defaults.put("threshold.minCapacity", new DefaultConfig(new BigDecimal("0.80"), "最小容量匹配阈值"));
        defaults.put("threshold.maxCapacity", new DefaultConfig(new BigDecimal("1.50"), "最大容量匹配阈值"));
        defaults.put("threshold.facilityMatch", new DefaultConfig(new BigDecimal("0.60"), "设施匹配最低阈值"));
        defaults.put("threshold.minScore", new DefaultConfig(new BigDecimal("60.00"), "最低推荐分数"));

        defaults.forEach((key, value) -> {
            Optional<WeightConfig> existing = weightConfigRepository.findByConfigKey(key);
            if (existing.isEmpty()) {
                WeightConfig config = new WeightConfig();
                config.setConfigKey(key);
                config.setConfigValue(value.value);
                config.setDescription(value.description);
                weightConfigRepository.save(config);
                log.info("初始化默认配置: {} = {}", key, value.value);
            }
        });
    }

    public void loadConfigsToRedis() {
        List<WeightConfig> configs = weightConfigRepository.findAll();
        Map<String, BigDecimal> configMap = new HashMap<>();
        configs.forEach(config -> configMap.put(config.getConfigKey(), config.getConfigValue()));
        hashOperations.putAll(WEIGHT_CONFIG_KEY, configMap);
        log.info("权重配置已加载到Redis");
    }

    public BigDecimal getWeight(String key) {
        BigDecimal value = hashOperations.get(WEIGHT_CONFIG_KEY, key);
        if (value == null) {
            Optional<WeightConfig> config = weightConfigRepository.findByConfigKey(key);
            if (config.isPresent()) {
                value = config.get().getConfigValue();
                hashOperations.put(WEIGHT_CONFIG_KEY, key, value);
            } else {
                value = BigDecimal.ZERO;
            }
        }
        return value;
    }

    public BigDecimal updateWeight(String key, BigDecimal value) {
        Optional<WeightConfig> existing = weightConfigRepository.findByConfigKey(key);
        WeightConfig config;
        if (existing.isPresent()) {
            config = existing.get();
            config.setConfigValue(value);
        } else {
            config = new WeightConfig();
            config.setConfigKey(key);
            config.setConfigValue(value);
        }
        weightConfigRepository.save(config);
        hashOperations.put(WEIGHT_CONFIG_KEY, key, value);
        log.info("更新权重配置: {} = {}", key, value);
        return value;
    }

    public Map<String, BigDecimal> getAllWeights() {
        return hashOperations.entries(WEIGHT_CONFIG_KEY);
    }

    private static class DefaultConfig {
        BigDecimal value;
        String description;

        DefaultConfig(BigDecimal value, String description) {
            this.value = value;
            this.description = description;
        }
    }
}
