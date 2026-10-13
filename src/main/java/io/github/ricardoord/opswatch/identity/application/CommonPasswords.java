package io.github.ricardoord.opswatch.identity.application;

import io.github.ricardoord.opswatch.shared.error.InvalidFieldException;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Component;

/**
 * Passwords from public leaks, rejected when a password is set (docs/security/security-architecture.md#contraseñas):
 * the entries of the xato.net top million, from SecLists, that {@code PasswordRules} accepts.
 * {@code scripts/common-passwords.mjs} regenerates the resource, and its source and license are in
 * {@code common-passwords.NOTICE}.
 *
 * <p>The list ships inside the jar: neither the password nor its hash leaves the process. It is compared in lowercase,
 * because changing the case of a leaked password does not make it any harder to guess.
 */
@Component
public class CommonPasswords {

    static final String RESOURCE = "classpath:identity/common-passwords.txt";
    static final String CONSTRAINT = "common-password";
    static final String MESSAGE = "must not be a commonly used password";

    private final Set<String> passwords;

    /** Loads the list once, at startup. A missing or empty resource stops the application from starting. */
    @Autowired
    public CommonPasswords(ResourceLoader resourceLoader) {
        this(read(resourceLoader.getResource(RESOURCE)));
    }

    CommonPasswords(Collection<String> passwords) {
        if (passwords.isEmpty()) {
            throw new IllegalStateException(RESOURCE + " is empty: run scripts/common-passwords.mjs");
        }
        this.passwords = passwords.stream().map(CommonPasswords::normalize).collect(Collectors.toUnmodifiableSet());
    }

    public boolean contains(String password) {
        return passwords.contains(normalize(password));
    }

    /**
     * @param field the request field that carries the password, for the error
     * @throws InvalidFieldException with the code {@code common-password} on {@code field}, and a message that never
     *     includes the password
     */
    public void rejectIfCommon(String field, String password) {
        if (contains(password)) {
            throw new InvalidFieldException(field, CONSTRAINT, MESSAGE);
        }
    }

    int size() {
        return passwords.size();
    }

    private static String normalize(String password) {
        // Not the default locale: in Turkish, "I" would become a dotless "ı"
        return password.toLowerCase(Locale.ROOT);
    }

    private static List<String> read(Resource resource) {
        if (!resource.exists()) {
            throw new IllegalStateException(RESOURCE + " is missing: run scripts/common-passwords.mjs");
        }
        try (BufferedReader reader =
                new BufferedReader(new InputStreamReader(resource.getInputStream(), StandardCharsets.UTF_8))) {
            return reader.lines().toList();
        } catch (IOException ex) {
            throw new UncheckedIOException("Cannot read " + RESOURCE, ex);
        }
    }
}
