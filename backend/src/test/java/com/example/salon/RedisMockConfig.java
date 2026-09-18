package com.example.salon;

import org.mockito.Mockito;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.RedisTemplate;

import java.util.HashMap;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;

/**
 * 集成测试用：不连真实 Redis，用内存 Map 顶替权重配置的 Hash 操作。
 * 与生产 RedisConfig 的 bean 不同名，仅靠 @Primary 让按类型注入处选中本 mock。
 */
@TestConfiguration
public class RedisMockConfig {

    @Bean("redisTemplateMock")
    @Primary
    @SuppressWarnings({"unchecked", "rawtypes"})
    public RedisTemplate<String, Object> redisTemplateMock() {
        RedisTemplate<String, Object> template = Mockito.mock(RedisTemplate.class);
        Map<String, Map<Object, Object>> store = new HashMap<>();
        HashOperations hashOps = Mockito.mock(HashOperations.class);

        Mockito.when(hashOps.entries(anyString())).thenAnswer(inv ->
                new HashMap<>(store.getOrDefault(inv.getArgument(0), Map.of())));
        Mockito.when(hashOps.get(anyString(), any())).thenAnswer(inv ->
                store.getOrDefault(inv.getArgument(0), Map.of()).get(inv.getArgument(1)));
        Mockito.doAnswer(inv -> {
            String key = inv.getArgument(0);
            store.computeIfAbsent(key, k -> new HashMap<>()).putAll(inv.getArgument(1));
            return null;
        }).when(hashOps).putAll(anyString(), anyMap());
        Mockito.doAnswer(inv -> {
            String key = inv.getArgument(0);
            store.computeIfAbsent(key, k -> new HashMap<>()).put(inv.getArgument(1), inv.getArgument(2));
            return null;
        }).when(hashOps).put(anyString(), any(), any());

        Mockito.when(template.opsForHash()).thenReturn(hashOps);
        return template;
    }
}
