package io.github.ricardoord.opswatch.notification.web;

import static io.github.ricardoord.opswatch.identity.domain.UserBuilder.uniqueEmail;
import static org.assertj.core.api.Assertions.assertThat;

import com.jayway.jsonpath.JsonPath;
import io.github.ricardoord.opswatch.IntegrationTest;
import io.github.ricardoord.opswatch.identity.security.AccessTokenIssuer;
import io.github.ricardoord.opswatch.notification.NotificationRows;
import io.github.ricardoord.opswatch.organization.Role;
import java.io.UnsupportedEncodingException;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MockMvcTester.MockMvcRequestBuilder;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

/**
 * {@code POST /api/v1/notification-channels/{channelId}/test} and {@code GET …/deliveries}, through the real security
 * chain. The worker does not run in the shared context: the deliveries stay as the API leaves them.
 */
@IntegrationTest
class DeliveryApiIT {

    private static final String RECIPIENT = "oncall@example.com";

    @Autowired
    private MockMvcTester mvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private AccessTokenIssuer tokens;

    @Test
    void aTestQueuesATestDeliveryThatShowsAmongTheDeliveriesOfTheChannel() {
        Organization org = newOrganization();
        String channel = newChannel(org);

        MvcTestResult tested = test(org.owner(), channel);

        assertThat(tested).hasStatus(202).bodyJson().isLenientlyEqualTo("""
                {"channelId": "%s", "incidentId": null, "eventType": "TEST", "status": "PENDING", "attempts": 0,
                 "lastAttemptAt": null, "lastError": null, "sentAt": null}""".formatted(channel));
        String delivery = read(tested, "$.id");
        MvcTestResult listed = deliveries(org.owner(), channel, "");
        assertThat(listed).hasStatus(200).bodyJson().isLenientlyEqualTo("""
                {"page": {"totalElements": 1}}""");
        assertThat(JsonPath.<List<String>>read(content(listed), "$.items[*].id"))
                .containsExactly(delivery);
        // Its status, never what is sent nor to whom
        assertThat(content(listed)).doesNotContain(RECIPIENT).doesNotContain("o***@example.com");
    }

    /** T-35: 5 tests per minute per channel, so that a channel cannot be used to send at will. */
    @Test
    void theSixthTestOfAChannelInAMinuteIsRejected() {
        Organization org = newOrganization();
        String channel = newChannel(org);
        for (int i = 0; i < 5; i++) {
            assertThat(test(org.owner(), channel)).hasStatus(202);
        }

        MvcTestResult rejected = test(org.owner(), channel);

        assertThat(rejected)
                .hasStatus(429)
                .hasHeader(HttpHeaders.RETRY_AFTER, "12")
                .bodyJson()
                .extractingPath("$.type")
                .asString()
                .endsWith("rate-limited");
        assertThat(test(org.owner(), newChannel(org))).as("per channel").hasStatus(202);
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM notification_deliveries WHERE channel_id = ?",
                        Long.class,
                        UUID.fromString(channel)))
                .isEqualTo(5);
    }

    /** Authorized before the rate limit: whoever may not test the channel never uses up its tests. */
    @Test
    void onlyThoseWhoMayChangeTheChannelTestItAndTheirRefusalsUseUpNoTest() {
        Organization org = newOrganization();
        String channel = newChannel(org);
        Caller viewer = memberOf(org.id(), Role.VIEWER);
        Caller member = memberOf(org.id(), Role.MEMBER);
        Caller stranger = memberOf(newOrganization().id(), Role.OWNER);

        for (int i = 0; i < 6; i++) {
            assertThat(test(viewer, channel)).hasStatus(403);
            assertThat(test(member, channel)).hasStatus(403);
            assertThat(test(stranger, channel)).hasStatus(404);
        }

        assertThat(test(memberOf(org.id(), Role.ADMIN), channel)).hasStatus(202);
        // As the channel itself: its deliveries say where it sends, which a VIEWER does not see
        assertThat(deliveries(member, channel, "")).hasStatus(200);
        assertThat(deliveries(viewer, channel, "")).hasStatus(403);
        assertThat(deliveries(stranger, channel, ""))
                .hasStatus(404)
                .bodyJson()
                .extractingPath("$.detail")
                .asString()
                .contains("notification channel");
    }

    @Test
    void aChannelThatDoesNotExistIsNotFound() {
        Organization org = newOrganization();
        String missing = UUID.randomUUID().toString();

        assertThat(test(org.owner(), missing)).hasStatus(404);
        assertThat(deliveries(org.owner(), missing, "")).hasStatus(404);
    }

    @Test
    void listsTheNewestFirstWithTheirAttemptsAndWhatWentWrong() {
        Organization org = newOrganization();
        String channel = newChannel(org);
        UUID project = new NotificationRows(jdbc).project(org.id());
        UUID incident = new NotificationRows(jdbc).openIncident(org.id(), project, "Payments API", Instant.now());
        UUID sent = delivery(channel, incident, "INCIDENT_OPENED", "SENT", 1, null, "2026-10-08T10:00:00Z");
        UUID failed = delivery(
                channel, incident, "INCIDENT_RESOLVED", "FAILED", 6, "SMTP server unreachable", "2026-10-08T11:00:00Z");

        MvcTestResult newestFirst = deliveries(org.owner(), channel, "");

        assertThat(JsonPath.<List<String>>read(content(newestFirst), "$.items[*].id"))
                .containsExactly(failed.toString(), sent.toString());
        assertThat(newestFirst).bodyJson().isLenientlyEqualTo("""
                {"items": [
                  {"incidentId": "%s", "eventType": "INCIDENT_RESOLVED", "status": "FAILED", "attempts": 6,
                   "lastError": "SMTP server unreachable", "nextAttemptAt": null},
                  {"eventType": "INCIDENT_OPENED", "status": "SENT", "attempts": 1, "lastError": null}]}""".formatted(incident));
        assertThat(JsonPath.<List<String>>read(
                        content(deliveries(org.owner(), channel, "?sort=createdAt,asc")), "$.items[*].id"))
                .containsExactly(sent.toString(), failed.toString());
        assertThat(deliveries(org.owner(), channel, "?sort=status")).hasStatus(400);
    }

    @Test
    void isDocumentedInOpenApiWithItsErrors() {
        MvcTestResult result = mvc.get().uri("/v3/api-docs").exchange();

        assertThat(result)
                .bodyJson()
                .extractingPath("$.paths['/api/v1/notification-channels/{channelId}/test'].post.responses")
                .asMap()
                .containsKeys("202", "401", "403", "404", "429");
        assertThat(result)
                .bodyJson()
                .extractingPath("$.paths['/api/v1/notification-channels/{channelId}/deliveries'].get.responses")
                .asMap()
                .containsKeys("200", "400", "401", "403", "404");
    }

    private UUID delivery(
            String channel,
            UUID incident,
            String eventType,
            String status,
            int attempts,
            String lastError,
            String createdAt) {
        UUID id = UUID.randomUUID();
        jdbc.update(
                """
                INSERT INTO notification_deliveries (id, channel_id, incident_id, event_type, status, attempts,
                                                     last_attempt_at, last_error, created_at, sent_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
                id,
                UUID.fromString(channel),
                incident,
                eventType,
                status,
                attempts,
                NotificationRows.at(Instant.parse(createdAt)),
                lastError,
                NotificationRows.at(Instant.parse(createdAt)),
                "SENT".equals(status) ? NotificationRows.at(Instant.parse(createdAt)) : null);
        return id;
    }

    private String newChannel(Organization org) {
        MvcTestResult created = as(
                        org.owner(),
                        mvc.post()
                                .uri("/api/v1/organizations/" + org.id() + "/notification-channels")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""
                                {"name": "On call", "type": "EMAIL", "email": {"recipients": ["%s"]}}""".formatted(RECIPIENT)))
                .exchange();
        assertThat(created).hasStatus(201);
        return read(created, "$.id");
    }

    private MvcTestResult test(Caller caller, String channel) {
        return as(caller, mvc.post().uri("/api/v1/notification-channels/" + channel + "/test"))
                .exchange();
    }

    private MvcTestResult deliveries(Caller caller, String channel, String query) {
        return as(caller, mvc.get().uri("/api/v1/notification-channels/" + channel + "/deliveries" + query))
                .exchange();
    }

    private static MockMvcRequestBuilder as(Caller caller, MockMvcRequestBuilder request) {
        return request.header(HttpHeaders.AUTHORIZATION, "Bearer " + caller.token());
    }

    private static String read(MvcTestResult result, String path) {
        return JsonPath.read(content(result), path);
    }

    private static String content(MvcTestResult result) {
        try {
            return result.getResponse().getContentAsString();
        } catch (UnsupportedEncodingException ex) {
            throw new IllegalStateException(ex);
        }
    }

    private Organization newOrganization() {
        UUID organization = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO organizations (id, name, created_at, updated_at) VALUES (?, 'CharityLink', now(), now())",
                organization);
        return new Organization(organization, memberOf(organization, Role.OWNER));
    }

    private Caller memberOf(UUID organization, Role role) {
        UUID user = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO users (id, email, display_name, password_hash, created_at, updated_at)
                VALUES (?, ?, 'Ana', '{bcrypt}not-a-real-hash', now(), now())""", user, uniqueEmail());
        jdbc.update("""
                INSERT INTO memberships (organization_id, user_id, role, created_at, updated_at)
                VALUES (?, ?, ?, now(), now())""", organization, user, role.name());
        return new Caller(tokens.issue(user).value());
    }

    /** A signed-in user: an access token. */
    private record Caller(String token) {}

    private record Organization(UUID id, Caller owner) {}
}
