package io.github.ricardoord.opswatch.notification.delivery;

import io.github.ricardoord.opswatch.incident.IncidentSummary;
import io.github.ricardoord.opswatch.incident.Resolution;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.Context;
import org.thymeleaf.templatemode.TemplateMode;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;

/**
 * Renders a notice as an email, in text and in HTML, with the templates of {@code notification/email/}. Thymeleaf core
 * with an engine of its own: no starter, no {@code ViewResolver}, nothing of the web layer.
 *
 * <p>The HTML templates write every value with {@code th:text}, which escapes it (T-34): a monitor name is typed by a
 * user. The subject is a header, so it never keeps a line break or another control character.
 */
@Component
class EmailTemplates {

    static final String LOCATION = "notification/email/";

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss 'UTC'", Locale.ROOT)
            .withZone(ZoneOffset.UTC);

    /** Control characters, line breaks included, and the Unicode line and paragraph separators. */
    private static final Pattern NOT_FOR_A_HEADER = Pattern.compile("[\\p{Cntrl}\\u2028\\u2029]+");

    private static final int SUBJECT_MAX_LENGTH = 200;

    private final TemplateEngine engine;

    EmailTemplates() {
        this.engine = new TemplateEngine();
        engine.addTemplateResolver(resolver(TemplateMode.HTML, ".html", 1));
        engine.addTemplateResolver(resolver(TemplateMode.TEXT, ".txt", 2));
    }

    EmailContent render(Notice notice) {
        String template = switch (notice.eventType()) {
            case INCIDENT_OPENED -> "incident-opened";
            case INCIDENT_RESOLVED -> "incident-resolved";
            case TEST -> "test";
        };
        Context context = new Context(Locale.ENGLISH, variables(notice));
        return new EmailContent(
                subject(notice),
                engine.process(template + ".txt", context),
                engine.process(template + ".html", context));
    }

    /** Plain values only: the expressions of the templates never call into the application. */
    private static Map<String, Object> variables(Notice notice) {
        Map<String, Object> variables = new HashMap<>();
        variables.put("channelName", notice.channelName());
        variables.put("deliveryId", notice.deliveryId().toString());
        IncidentSummary incident = notice.incident();
        if (incident != null) {
            variables.put("incidentId", incident.id().toString());
            variables.put("monitorName", incident.monitorName());
            variables.put("cause", incident.cause());
            variables.put(
                    "httpStatus",
                    incident.httpStatus() == null ? "" : incident.httpStatus().toString());
            variables.put("openedAt", TIME.format(incident.openedAt()));
            Instant resolvedAt = incident.resolvedAt();
            if (resolvedAt != null) {
                variables.put("resolvedAt", TIME.format(resolvedAt));
                variables.put("duration", duration(Duration.between(incident.openedAt(), resolvedAt)));
                variables.put("resolution", resolution(Objects.requireNonNull(incident.resolution())));
            }
        }
        return variables;
    }

    private static String subject(Notice notice) {
        String subject = switch (notice.eventType()) {
            case INCIDENT_OPENED -> "[OpsWatch] DOWN: " + monitorName(notice);
            case INCIDENT_RESOLVED -> "[OpsWatch] RESOLVED: " + monitorName(notice);
            case TEST -> "[OpsWatch] Test notification: " + notice.channelName();
        };
        String oneLine = NOT_FOR_A_HEADER.matcher(subject).replaceAll(" ").strip();
        return oneLine.length() > SUBJECT_MAX_LENGTH ? oneLine.substring(0, SUBJECT_MAX_LENGTH) : oneLine;
    }

    private static String monitorName(Notice notice) {
        return Objects.requireNonNull(notice.incident()).monitorName();
    }

    private static String resolution(Resolution resolution) {
        return switch (resolution) {
            case AUTO_RECOVERED -> "the monitor recovered";
            case MONITOR_PAUSED -> "the monitor was paused";
            case MONITOR_DELETED -> "the monitor was deleted";
        };
    }

    /** {@code 45 s}, {@code 10 min 3 s}, {@code 2 h 5 min}, {@code 3 d 4 h}: the two largest units. */
    static String duration(Duration duration) {
        long seconds = Math.max(0, duration.toSeconds());
        long days = seconds / 86_400;
        long hours = seconds % 86_400 / 3_600;
        long minutes = seconds % 3_600 / 60;
        long rest = seconds % 60;
        if (days > 0) {
            return days + " d " + hours + " h";
        }
        if (hours > 0) {
            return hours + " h " + minutes + " min";
        }
        if (minutes > 0) {
            return minutes + " min " + rest + " s";
        }
        return rest + " s";
    }

    private static ClassLoaderTemplateResolver resolver(TemplateMode mode, String suffix, int order) {
        ClassLoaderTemplateResolver resolver = new ClassLoaderTemplateResolver(EmailTemplates.class.getClassLoader());
        resolver.setPrefix(LOCATION);
        resolver.setTemplateMode(mode);
        resolver.setResolvablePatterns(Set.of("*" + suffix));
        resolver.setCharacterEncoding("UTF-8");
        resolver.setCacheable(true);
        resolver.setOrder(order);
        return resolver;
    }

    /** @param text and {@code html} are the same message, for the client to pick */
    record EmailContent(String subject, String text, String html) {}
}
