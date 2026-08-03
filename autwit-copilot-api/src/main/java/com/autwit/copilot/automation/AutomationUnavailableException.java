package com.autwit.copilot.automation;

/**
 * The AUTWIT run service is not configured or not reachable.
 *
 * <p>Surfaced as 503, not 500: nothing in copilot is broken, and the distinction is what
 * tells a tester to check whether the runner is up rather than to raise a copilot bug.</p>
 */
public class AutomationUnavailableException extends RuntimeException {

    public AutomationUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
