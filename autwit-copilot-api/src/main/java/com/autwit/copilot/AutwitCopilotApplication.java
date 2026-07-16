package com.autwit.copilot;

import com.autwit.copilot.compare.FinancialProperties;
import com.autwit.copilot.config.AutwitProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Profiles double as BUILD_BRIEF §5's {@code --mode=api|worker|all}:
 * <ul>
 *   <li>{@code all} (default) — API and worker in one process
 *   <li>{@code worker} — worker only
 *   <li>{@code api} — API only; something else must run the worker
 *   <li>{@code fake} — replay fixtures instead of calling a real orchestrator
 * </ul>
 * The worker always dequeues through Postgres regardless, so splitting it out later
 * is a config change rather than a rewrite.
 */
@SpringBootApplication
@EnableConfigurationProperties({AutwitProperties.class, FinancialProperties.class})
@EnableScheduling
public class AutwitCopilotApplication {

    public static void main(String[] args) {
        SpringApplication.run(AutwitCopilotApplication.class, args);
    }
}
