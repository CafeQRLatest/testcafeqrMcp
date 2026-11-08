package com.restaurant.pos.ai;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class CafeQrAiApplication {
    public static void main(String[] args) {
        SpringApplication.run(CafeQrAiApplication.class, args);
    }
}
