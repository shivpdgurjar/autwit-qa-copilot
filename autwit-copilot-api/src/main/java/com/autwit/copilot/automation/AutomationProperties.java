package com.autwit.copilot.automation;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Where the AUTWIT run service lives.
 *
 * <p>copilot does not execute automation runs — it proxies them. Workspace provisioning
 * and process lifecycle belong to the machine the tests run on, so the run registry is
 * owned there (autwit: docs/REMOTE_RUN_TRIGGER_MODEL.md). This service exists so the
 * browser talks to one origin, with one authentication story, and so SSE and an embedded
 * report both work without CORS.</p>
 */
@ConfigurationProperties(prefix = "autwit.automation")
public class AutomationProperties {

    /** Base URL of the AUTWIT run service. Empty disables the automation plane. */
    private String baseUrl = "";

    /** Bearer token, if the run service requires one. */
    private String token = "";

    /**
     * Deliberately short. Every call proxied here is a registry read or a submit — the
     * run itself is asynchronous, so nothing on this path should ever be slow.
     */
    private Duration timeout = Duration.ofSeconds(15);

    public String getBaseUrl() { return baseUrl; }
    public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }

    public String getToken() { return token; }
    public void setToken(String token) { this.token = token; }

    public Duration getTimeout() { return timeout; }
    public void setTimeout(Duration timeout) { this.timeout = timeout; }

    public boolean isEnabled() {
        return baseUrl != null && !baseUrl.isBlank();
    }
}
