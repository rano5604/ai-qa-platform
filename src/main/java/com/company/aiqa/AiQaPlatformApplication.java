package com.company.aiqa;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;

@SpringBootApplication
@EnableAsync
public class AiQaPlatformApplication {
    public static void main(String[] args) {
        SpringApplication.run(AiQaPlatformApplication.class, args);
    }
}
