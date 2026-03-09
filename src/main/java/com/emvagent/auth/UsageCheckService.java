package com.emvagent.auth;

/**
 * Public contract for usage/subscription enforcement.
 * Implemented by BillingService in the same package;
 * injected by ChatService (different package) via this interface.
 */
public interface UsageCheckService {

    /**
     * Verify the user has an active subscription and is within usage limits.
     * Throws ResponseStatusException (402 or 429) if checks fail.
     * Increments usage counters and saves on success.
     */
    void checkAndIncrementUsage(String username);
}
