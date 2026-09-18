package com.example.salon.repository;

import com.example.salon.entity.WeightConfig;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface WeightConfigRepository extends JpaRepository<WeightConfig, Long> {

    Optional<WeightConfig> findByConfigKey(String configKey);
}
