package com.autwit.copilot;

import com.autwit.copilot.config.AutwitProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties(AutwitProperties.class)
public class AutwitCopilotApplication {

    public static void main(String[] args) {
        SpringApplication.run(AutwitCopilotApplication.class, args);
    }
}
