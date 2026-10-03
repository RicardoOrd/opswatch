package io.github.ricardoord.opswatch;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

/**
 * The whole application against PostgreSQL, with the {@code test} profile, its own JWT keys ({@link TestJwtKeys}), a
 * fake DNS ({@link TestHostResolver}) and {@code MockMvcTester} over the real security configuration. Every test class
 * that uses it shares one cached Spring context, so it only starts once.
 *
 * <p>Tests share the database too: each one creates its own data (unique emails, fresh ids) instead of cleaning up.
 */
@Documented
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@SpringBootTest
@AutoConfigureMockMvc
@Import({PostgresTestcontainer.class, TestJwtKeys.class, TestEncryptionKeys.class, TestHostResolver.class})
@ActiveProfiles("test")
public @interface IntegrationTest {}
