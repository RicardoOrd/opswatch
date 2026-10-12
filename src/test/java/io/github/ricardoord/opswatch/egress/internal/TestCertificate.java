package io.github.ricardoord.opswatch.egress.internal;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;

/**
 * A self-signed certificate for some host names, made by the {@code keytool} of the running JDK in a temporary
 * directory: an {@code https} target on the loopback (WireMock) that a client of {@code egress} trusts through
 * {@link TestEgressHttpClients#trusting}, hostname verification included. No key lives in the repository.
 */
public final class TestCertificate {

    public static final String PASSWORD = "changeit";

    private static final String ALIAS = "target";

    private final Path keystore;

    private TestCertificate(Path keystore) {
        this.keystore = keystore;
    }

    /** Valid for two days from now, for every name given. */
    public static TestCertificate forHosts(String... hosts) {
        try {
            Path keystore = Files.createTempDirectory("opswatch-tls").resolve("target.p12");
            List<String> command = new ArrayList<>(List.of(
                    keytool(),
                    "-genkeypair",
                    "-alias",
                    ALIAS,
                    "-keyalg",
                    "RSA",
                    "-keysize",
                    "2048",
                    "-dname",
                    "CN=" + hosts[0],
                    "-ext",
                    "SAN="
                            + String.join(
                                    ",",
                                    List.of(hosts).stream()
                                            .map(host -> "dns:" + host)
                                            .toList()),
                    "-validity",
                    "2",
                    "-storetype",
                    "PKCS12",
                    "-keystore",
                    keystore.toString(),
                    "-storepass",
                    PASSWORD,
                    "-keypass",
                    PASSWORD,
                    "-noprompt"));
            Process keytool =
                    new ProcessBuilder(command).redirectErrorStream(true).start();
            String output = new String(keytool.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            if (!keytool.waitFor(60, TimeUnit.SECONDS) || keytool.exitValue() != 0) {
                throw new IllegalStateException("keytool failed: " + output);
            }
            return new TestCertificate(keystore);
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(ex);
        }
    }

    /** For the {@code https} side of WireMock, with {@link #PASSWORD}. */
    public String keystorePath() {
        return keystore.toString();
    }

    /** Trusts this certificate and nothing else. */
    public SSLContext trust() {
        try (InputStream in = Files.newInputStream(keystore)) {
            KeyStore keys = KeyStore.getInstance("PKCS12");
            keys.load(in, PASSWORD.toCharArray());
            KeyStore trusted = KeyStore.getInstance("PKCS12");
            trusted.load(null, null);
            trusted.setCertificateEntry(ALIAS, keys.getCertificate(ALIAS));
            TrustManagerFactory factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            factory.init(trusted);
            SSLContext context = SSLContext.getInstance("TLS");
            context.init(null, factory.getTrustManagers(), null);
            return context;
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException(ex);
        }
    }

    /** Serves this certificate, for a hand-made {@code https} target that WireMock cannot play. */
    public SSLContext serve() {
        try (InputStream in = Files.newInputStream(keystore)) {
            KeyStore keys = KeyStore.getInstance("PKCS12");
            keys.load(in, PASSWORD.toCharArray());
            KeyManagerFactory factory = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
            factory.init(keys, PASSWORD.toCharArray());
            SSLContext context = SSLContext.getInstance("TLS");
            context.init(factory.getKeyManagers(), null, null);
            return context;
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException(ex);
        }
    }

    private static String keytool() {
        boolean windows = System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win");
        return Path.of(System.getProperty("java.home"), "bin", windows ? "keytool.exe" : "keytool")
                .toString();
    }
}
