package io.github.ricardoord.opswatch.monitoring;

import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;

/**
 * What the build must refuse: a class of {@code monitoring} that builds its own HTTP client instead of asking
 * {@code egress} for one. Only {@code ArchitectureRulesTests} uses it, to show that the rule catches it; it lives in
 * the test sources, so it never reaches the application.
 */
public final class HandMadeHttpClient {

    private HandMadeHttpClient() {}

    public static CloseableHttpClient build() {
        return HttpClients.createDefault();
    }
}
