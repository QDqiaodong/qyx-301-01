package com.example.salon;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cache.annotation.EnableCaching;

@SpringBootApplication
@EnableCaching
public class SalonRecommendApplication {

    public static void main(String[] args) {
        SpringApplication.run(SalonRecommendApplication.class, args);
    }
}
