package io.github.ricardoord.opswatch.egress.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.ricardoord.opswatch.egress.TargetKind;
import io.github.ricardoord.opswatch.shared.error.TargetNotAllowedException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Layer 1 against the cases of docs/security/ssrf-protection.md#5-casos-de-prueba-obligatorios that are decided on
 * saving, with a resolver that answers what each test says: none depends on the real DNS.
 */
class DefaultTargetPolicyTest {

    private static final String HOST_NOT_ALLOWED = "The host is not allowed.";

    private final FakeResolver resolver = new FakeResolver()
            .with("example.com", "93.184.216.34")
            .with("api.example.com", "93.184.216.34", "2606:2800:220:1:248:1893:25c8:1946")
            .with("xn--ejmplo-cva.com", "93.184.216.35")
            .with("internal-looking.example.com", "10.0.0.5")
            .with("mixed.example.com", "93.184.216.34", "10.0.0.5")
            .with("rebind.example.com", "127.0.0.1")
            .with("nat64.example.com", "64:ff9b::a9fe:a9fe");

    private final DefaultTargetPolicy policy = policy(List.of(), Duration.ofSeconds(2));

    @Test
    void acceptsAPublicUrlAndNormalizesIt() {
        assertThat(policy.validate("HTTP://Example.COM:8080/Health?Verbose=1#section", TargetKind.MONITOR))
                .hasToString("http://example.com:8080/Health?Verbose=1");
        assertThat(policy.validate("https://api.example.com/health", TargetKind.MONITOR))
                .hasToString("https://api.example.com/health");
        assertThat(policy.validate("http://example.com.", TargetKind.MONITOR)).hasToString("http://example.com");
    }

    @Test
    void turnsAnInternationalizedNameIntoPunycode() {
        String accented = "ej" + Character.toString(0xE9) + "mplo.com";

        assertThat(policy.validate("https://" + accented + "/x", TargetKind.MONITOR))
                .hasToString("https://xn--ejmplo-cva.com/x");
    }

    /** Cases 1 to 4, 6, 7, 9, 10 and 11: addresses that are not public, written as literals. */
    @ParameterizedTest
    @ValueSource(
            strings = {
                "http://127.0.0.1/",
                "http://[::1]/",
                "http://[::ffff:127.0.0.1]/",
                "http://0.0.0.0/",
                "http://169.254.169.254/latest/meta-data/",
                "http://100.100.100.200/",
                "http://[fd00:ec2::254]/",
                "http://10.0.0.1/",
                "http://172.16.5.4/",
                "http://192.168.1.1/",
                "http://[fe80::1%25eth0]/"
            })
    void rejectsAddressesThatAreNotPublic(String url) {
        assertRejected(url, HOST_NOT_ALLOWED);
    }

    /** Case 5: forms of an address that resolvers read each in its own way. */
    @ParameterizedTest
    @ValueSource(
            strings = {
                "http://2130706433/",
                "http://0x7f000001/",
                "http://0177.0.0.1/",
                "http://127.1/",
                "http://0x7f.0.0.1/",
                "http://1.2.3.04/"
            })
    void rejectsAddressesInAnyFormButDottedDecimal(String url) {
        assertRejected(url, HOST_NOT_ALLOWED);
    }

    /** Cases 2, 8 and 12: names that only mean something inside a network. */
    @ParameterizedTest
    @ValueSource(
            strings = {
                "http://localhost/",
                "http://localhost./",
                "http://LOCALHOST/",
                "http://app.localhost/",
                "http://printer.local/",
                "http://metadata.google.internal/",
                "http://router.home.arpa/",
                "http://home.arpa/",
                "http://postgres:5432/",
                "http://my_host.example.com/",
                "http://%6c%6f%63%61%6c%68%6f%73%74/",
                "http://-bad-.example.com/"
            })
    void rejectsInternalAndMalformedNames(String url) {
        assertRejected(url, HOST_NOT_ALLOWED);
    }

    /** Case 13. */
    @ParameterizedTest
    @ValueSource(strings = {"file:///etc/passwd", "gopher://example.com/", "ftp://example.com/", "jar:http://x!/"})
    void rejectsSchemesOtherThanHttpAndHttps(String url) {
        assertRejected(url, "Only http and https URLs are allowed.");
    }

    /** Case 14. */
    @ParameterizedTest
    @ValueSource(strings = {"http://usuario:clave@example.com/", "http://usuario@example.com/"})
    void rejectsCredentialsInTheUrl(String url) {
        assertRejected(url, "The URL must not contain credentials: send them in a header.");
    }

    /** Case 15, and ports below 1024 other than 80 and 443. */
    @ParameterizedTest
    @ValueSource(
            strings = {
                "http://example.com:22/",
                "http://example.com:25/",
                "http://example.com:0/",
                "http://example.com:99999/"
            })
    void rejectsPortsThatAreNotAllowed(String url) {
        assertRejected(url, "The port is not allowed: use 80, 443 or one from 1024 to 65535.");
    }

    @Test
    void acceptsTheWebPortsAndTheUnprivilegedOnes() {
        for (String port : List.of("80", "443", "1024", "8080", "65535")) {
            assertThat(policy.validate("http://example.com:" + port + "/", TargetKind.MONITOR))
                    .hasToString("http://example.com:" + port + "/");
        }
    }

    /** Case 26: a webhook carries data, so only over https. */
    @Test
    void aWebhookMustUseHttps() {
        assertThatThrownBy(() -> policy.validate("http://example.com/hook", TargetKind.WEBHOOK))
                .isInstanceOf(TargetNotAllowedException.class)
                .hasMessage("Only https URLs are allowed.");
        assertThat(policy.validate("https://example.com/hook", TargetKind.WEBHOOK))
                .hasToString("https://example.com/hook");
    }

    @ParameterizedTest
    @ValueSource(strings = {"not a url", "http:example.com", "//example.com/", "http:///path", "http://exa mple.com/"})
    void rejectsWhatIsNotAnAbsoluteUrl(String url) {
        assertThatThrownBy(() -> policy.validate(url, TargetKind.MONITOR))
                .isInstanceOf(TargetNotAllowedException.class);
    }

    @Test
    void rejectsUrlsLongerThan2048Characters() {
        String path = "/" + "a".repeat(2048 - "http://example.com/".length());

        assertThat(policy.validate("http://example.com" + path, TargetKind.MONITOR))
                .isNotNull();
        assertRejected("http://example.com" + path + "a", "The URL is too long: 2048 characters at most.");
    }

    /** Every address the name resolves to must be public, and the detail never shows them. */
    @Test
    void rejectsANameThatResolvesToAnAddressThatIsNotPublicWithoutShowingIt() {
        for (String host : List.of(
                "internal-looking.example.com", "mixed.example.com", "rebind.example.com", "nat64.example.com")) {
            assertThatThrownBy(() -> policy.validate("http://" + host + "/", TargetKind.MONITOR))
                    .isInstanceOf(TargetNotAllowedException.class)
                    .hasMessage(HOST_NOT_ALLOWED);
        }
    }

    @Test
    void doesNotResolveAnAddressLiteral() {
        assertThat(policy.validate("http://93.184.216.34/", TargetKind.MONITOR)).hasToString("http://93.184.216.34/");
        assertThat(policy.validate("http://[2606:4700:4700::1111]/", TargetKind.MONITOR))
                .hasToString("http://[2606:4700:4700::1111]/");
        assertThat(resolver.lookups).isEmpty();
    }

    /** Its DNS may not exist yet: layer 2 decides on every check, and the first one will show DNS_FAILURE. */
    @Test
    void acceptsANameThatDoesNotResolve() {
        assertThat(policy.validate("https://not-yet.example.com/health", TargetKind.MONITOR))
                .hasToString("https://not-yet.example.com/health");
    }

    @Test
    void doesNotWaitForSlowDnsLongerThanTheTimeout() {
        CountDownLatch never = new CountDownLatch(1);
        DefaultTargetPolicy impatient = policy(List.of(), Duration.ofMillis(200), host -> {
            await(never);
            throw new UnknownHostException(host);
        });
        long start = System.nanoTime();

        try {
            assertThat(impatient.validate("https://slow.example.com/", TargetKind.MONITOR))
                    .hasToString("https://slow.example.com/");
            assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(1));
        } finally {
            never.countDown();
        }
    }

    /** Lookups that never answer keep their permit, so they cannot pile up: beyond the limit, nothing is resolved. */
    @Test
    void neverRunsMoreLookupsAtOnceThanItsLimit() {
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger started = new AtomicInteger();
        DefaultTargetPolicy limited = policy(List.of(), Duration.ofMillis(20), host -> {
            started.incrementAndGet();
            await(release);
            throw new UnknownHostException(host);
        });

        try {
            for (int i = 0; i <= DefaultTargetPolicy.MAX_CONCURRENT_RESOLUTIONS; i++) {
                limited.validate("https://stuck-" + i + ".example.com/", TargetKind.MONITOR);
            }
            assertThat(started).hasValue(DefaultTargetPolicy.MAX_CONCURRENT_RESOLUTIONS);
        } finally {
            release.countDown();
        }
    }

    /** Case 25 at this layer: the metadata stays closed even when every private block is open. */
    @Test
    void allowedPrivateBlocksNeverOpenTheMetadata() {
        DefaultTargetPolicy permissive = policy(List.of("0.0.0.0/0", "::/0"), Duration.ofSeconds(2));

        assertThat(permissive.validate("http://127.0.0.1:8089/", TargetKind.MONITOR))
                .isNotNull();
        assertThat(permissive.validate("http://internal-looking.example.com/", TargetKind.MONITOR))
                .isNotNull();
        assertThatThrownBy(() -> permissive.validate("http://169.254.169.254/", TargetKind.MONITOR))
                .hasMessage(HOST_NOT_ALLOWED);
        assertThatThrownBy(() -> permissive.validate("http://nat64.example.com/", TargetKind.MONITOR))
                .hasMessage(HOST_NOT_ALLOWED);
        // Opening addresses does not open names: localhost stays forbidden
        assertThatThrownBy(() -> permissive.validate("http://localhost/", TargetKind.MONITOR))
                .hasMessage(HOST_NOT_ALLOWED);
    }

    private void assertRejected(String url, String detail) {
        assertThatThrownBy(() -> policy.validate(url, TargetKind.MONITOR))
                .isInstanceOf(TargetNotAllowedException.class)
                .hasMessage(detail);
    }

    private DefaultTargetPolicy policy(List<String> allowedPrivate, Duration timeout) {
        return policy(allowedPrivate, timeout, resolver);
    }

    private static DefaultTargetPolicy policy(List<String> allowedPrivate, Duration timeout, HostResolver resolver) {
        return new DefaultTargetPolicy(resolver, new EgressProperties(allowedPrivate, timeout));
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await(30, TimeUnit.SECONDS);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }

    /** Answers from a table; any other name does not resolve. Remembers what it was asked. */
    private static final class FakeResolver implements HostResolver {

        private final Map<String, List<InetAddress>> answers = new ConcurrentHashMap<>();
        private final List<String> lookups = new java.util.concurrent.CopyOnWriteArrayList<>();

        FakeResolver with(String host, String... addresses) {
            answers.put(
                    host, Arrays.stream(addresses).map(InetAddress::ofLiteral).toList());
            return this;
        }

        @Override
        public List<InetAddress> resolve(String host) throws UnknownHostException {
            lookups.add(host);
            List<InetAddress> addresses = answers.get(host);
            if (addresses == null) {
                throw new UnknownHostException(host);
            }
            return addresses;
        }
    }
}
