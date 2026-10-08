package io.github.ricardoord.opswatch.notification.web;

import static io.github.ricardoord.opswatch.identity.domain.UserBuilder.uniqueEmail;
import static org.assertj.core.api.Assertions.assertThat;

import com.jayway.jsonpath.JsonPath;
import io.github.ricardoord.opswatch.IntegrationTest;
import io.github.ricardoord.opswatch.TestHostResolver;
import io.github.ricardoord.opswatch.identity.security.AccessTokenIssuer;
import io.github.ricardoord.opswatch.notification.application.ChannelConfig;
import io.github.ricardoord.opswatch.notification.application.ChannelConfigs;
import io.github.ricardoord.opswatch.notification.domain.NotificationChannelRepository;
import io.github.ricardoord.opswatch.organization.Role;
import io.github.ricardoord.opswatch.organization.application.OrganizationService;
import io.github.ricardoord.opswatch.organization.application.ProjectService;
import io.github.ricardoord.opswatch.shared.crypto.DecryptionFailedException;
import java.io.UnsupportedEncodingException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.IntStream;
import org.assertj.core.api.Assertions;
import org.awaitility.Awaitility;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MockMvcTester.MockMvcRequestBuilder;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * {@code /api/v1/organizations/{orgId}/notification-channels} and {@code /api/v1/notification-channels/{channelId}},
 * through the real security chain: the configuration encrypted and masked, the signing secret shown once, the SSRF policy
 * on webhook URLs, the quota, and the cleanup with a deleted project or organization.
 */
@IntegrationTest
class ChannelApiIT {

    private static final String HOOK = "https://" + TestHostResolver.PUBLIC_HOST + "/hooks/T0/secret-token?key=abc";
    private static final Duration CLEANUP = Duration.ofSeconds(10);

    @Autowired
    private MockMvcTester mvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private AccessTokenIssuer tokens;

    @Autowired
    private ChannelConfigs configs;

    @Autowired
    private NotificationChannelRepository channels;

    @Autowired
    private ProjectService projects;

    @Autowired
    private OrganizationService organizations;

    @Autowired
    private TransactionTemplate transactions;

    @Test
    void createsAnEmailChannelWithItsRecipientsNormalizedAndMasked() {
        Organization org = newOrganization();

        MvcTestResult created = create(org.owner(), org.id(), """
                {"name": " Guardia ", "type": "EMAIL",
                 "email": {"recipients": ["OnCall@Example.com", "ana@charitylink.org"]}}""");

        assertThat(created)
                .hasStatus(201)
                .hasHeader(HttpHeaders.ETAG, "\"0\"")
                .bodyJson()
                .isLenientlyEqualTo("""
                {"organizationId": "%s", "projectId": null, "name": "Guardia", "type": "EMAIL", "enabled": true,
                 "email": {"recipients": ["o***@example.com", "a***@charitylink.org"]}, "webhook": null,
                 "version": 0}""".formatted(org.id()));
        String id = read(created, "$.id");
        assertThat(created.getResponse().getHeader(HttpHeaders.LOCATION))
                .isEqualTo("/api/v1/notification-channels/" + id);
        assertThat(unsealed(id))
                .isEqualTo(new ChannelConfig.Email(List.of("oncall@example.com", "ana@charitylink.org")));
    }

    /**
     * The secret leaves once, in the response that creates it, and again only when rotated. The URL comes back masked,
     * and neither of them is in clear in the database.
     */
    @Test
    void showsTheSigningSecretOnlyOnCreationAndOnRotation() {
        Organization org = newOrganization();

        MvcTestResult created = create(org.owner(), org.id(), webhookBody(HOOK));

        assertThat(created).hasStatus(201);
        String id = read(created, "$.id");
        String secret = read(created, "$.webhook.signingSecret");
        assertThat(secret).startsWith("whsec_").hasSize("whsec_".length() + 43);
        assertThat(JsonPath.<String>read(content(created), "$.webhook.url"))
                .isEqualTo("https://" + TestHostResolver.PUBLIC_HOST + "/…");
        MvcTestResult read = get(org.owner(), id);
        assertThat(read).hasStatus(200);
        assertThat(content(read))
                .doesNotContain(secret)
                .doesNotContain("secret-token")
                .contains("\"webhook\":{\"url\"");
        assertThat(content(list(org.owner(), org.id()))).doesNotContain(secret);
        String stored = new String(ciphertextOf(id), StandardCharsets.ISO_8859_1);
        assertThat(stored).doesNotContain(secret).doesNotContain("secret-token");

        MvcTestResult rotated = as(
                        org.owner(), mvc.post().uri("/api/v1/notification-channels/" + id + "/rotate-secret"))
                .exchange();

        assertThat(rotated).hasStatus(200).hasHeader(HttpHeaders.ETAG, "\"1\"");
        String newSecret = read(rotated, "$.webhook.signingSecret");
        assertThat(newSecret).startsWith("whsec_").isNotEqualTo(secret);
        assertThat(unsealed(id)).isEqualTo(new ChannelConfig.Webhook(HOOK, newSecret));
        assertThat(content(get(org.owner(), id))).doesNotContain(newSecret);
    }

    /** Case 26 of the SSRF table, and a URL that resolves to a private network. */
    @Test
    void rejectsAWebhookThatIsNotHttpsOrPointsToAPrivateNetwork() {
        Organization org = newOrganization();

        for (String url : List.of(
                "http://" + TestHostResolver.PUBLIC_HOST + "/hooks",
                "https://" + TestHostResolver.PRIVATE_HOST + "/hooks",
                "https://169.254.169.254/latest/meta-data/",
                "https://127.0.0.1:8443/hooks")) {
            assertThat(create(org.owner(), org.id(), webhookBody(url)))
                    .as(url)
                    .hasStatus(422)
                    .bodyJson()
                    .extractingPath("$.code")
                    .isEqualTo("target-not-allowed");
        }
        assertThat(channels.countByOrganizationId(org.id())).isZero();
    }

    @Test
    void rejectsAnInvalidChannel() {
        Organization org = newOrganization();
        String eleven = IntStream.rangeClosed(1, 11)
                .mapToObj(i -> "\"user" + i + "@example.com\"")
                .reduce((a, b) -> a + ", " + b)
                .orElseThrow();

        // The field each body gets wrong, and that body
        List<Map.Entry<String, String>> invalid = List.of(
                Map.entry("email", "{\"name\": \"Guardia\", \"type\": \"EMAIL\"}"),
                Map.entry(
                        "webhook",
                        "{\"name\": \"Guardia\", \"type\": \"EMAIL\", \"email\": {\"recipients\": [\"a@example.com\"]},"
                                + " \"webhook\": {\"url\": \"" + HOOK + "\"}}"),
                Map.entry(
                        "email.recipients",
                        "{\"name\": \"Guardia\", \"type\": \"EMAIL\","
                                + " \"email\": {\"recipients\": [\"Ana@example.com\", \"ana@example.com\"]}}"),
                Map.entry(
                        "email.recipients",
                        "{\"name\": \"Guardia\", \"type\": \"EMAIL\", \"email\": {\"recipients\": [" + eleven + "]}}"),
                Map.entry(
                        "email.recipients",
                        "{\"name\": \"Guardia\", \"type\": \"EMAIL\", \"email\": {\"recipients\": [\"not an address\"]}}"),
                Map.entry(
                        "email.recipients",
                        "{\"name\": \"Guardia\", \"type\": \"EMAIL\", \"email\": {\"recipients\": []}}"),
                Map.entry(
                        "name",
                        "{\"name\": \"  \", \"type\": \"EMAIL\", \"email\": {\"recipients\": [\"a@example.com\"]}}"),
                Map.entry("type", "{\"name\": \"Guardia\", \"email\": {\"recipients\": [\"a@example.com\"]}}"));
        for (Map.Entry<String, String> body : invalid) {
            assertThat(create(org.owner(), org.id(), body.getValue()))
                    .as(body.getValue())
                    .hasStatus(400)
                    .bodyJson()
                    .extractingPath("$.errors[*].field")
                    .asArray()
                    .anySatisfy(field -> assertThat((String) field).startsWith(body.getKey()));
        }
        assertThat(create(org.owner(), org.id(), "{\"name\": \"Guardia\", \"type\": \"SMS\"}"))
                .hasStatus(400);
        assertThat(channels.countByOrganizationId(org.id())).isZero();
    }

    @Test
    void theEleventhChannelOfAnOrganizationExceedsTheQuota() {
        Organization org = newOrganization();
        for (int i = 1; i <= 10; i++) {
            assertThat(create(org.owner(), org.id(), emailBody("Channel " + i))).hasStatus(201);
        }

        assertThat(create(org.owner(), org.id(), emailBody("Channel 11")))
                .hasStatus(422)
                .bodyJson()
                .extractingPath("$.code")
                .isEqualTo("quota-exceeded");
    }

    @Test
    void aChannelOfAProjectNeedsAProjectOfTheOrganization() {
        Organization org = newOrganization();
        UUID project = newProject(org.id());
        UUID foreign = newProject(newOrganization().id());

        MvcTestResult created = create(org.owner(), org.id(), """
                {"name": "Guardia", "type": "EMAIL", "projectId": "%s",
                 "email": {"recipients": ["a@example.com"]}}""".formatted(project));

        assertThat(created)
                .hasStatus(201)
                .bodyJson()
                .extractingPath("$.projectId")
                .isEqualTo(project.toString());
        assertThat(create(org.owner(), org.id(), """
                        {"name": "Guardia", "type": "EMAIL", "projectId": "%s",
                         "email": {"recipients": ["a@example.com"]}}""".formatted(foreign)))
                .hasStatus(404)
                .bodyJson()
                .extractingPath("$.detail")
                .isEqualTo("project " + foreign + " was not found");
    }

    /** Only what is sent changes; a new URL keeps the secret; If-Match must match. */
    @Test
    void changesOnlyWhatIsSent() {
        Organization org = newOrganization();
        UUID project = newProject(org.id());
        MvcTestResult created = create(org.owner(), org.id(), webhookBody(HOOK));
        String id = read(created, "$.id");
        String secret = read(created, "$.webhook.signingSecret");
        String newHook = "https://" + TestHostResolver.PUBLIC_HOST + "/other";

        MvcTestResult changed = patch(org.owner(), id, """
                {"name": "Slack", "enabled": false, "projectId": "%s", "webhook": {"url": "%s"}}""".formatted(project, newHook), "\"0\"");

        assertThat(changed)
                .hasStatus(200)
                .hasHeader(HttpHeaders.ETAG, "\"1\"")
                .bodyJson()
                .isLenientlyEqualTo("""
                {"name": "Slack", "enabled": false, "projectId": "%s",
                 "webhook": {"url": "https://%s/…"}}""".formatted(project, TestHostResolver.PUBLIC_HOST));
        assertThat(unsealed(id)).isEqualTo(new ChannelConfig.Webhook(newHook, secret));
        assertThat(patch(org.owner(), id, "{\"projectId\": null}", null))
                .hasStatus(200)
                .bodyJson()
                .extractingPath("$.projectId")
                .isNull();
        assertThat(patch(org.owner(), id, "{\"name\": \"Late\"}", "\"0\"")).hasStatus(412);
        assertThat(patch(org.owner(), id, "{\"name\": null}", null)).hasStatus(400);
        assertThat(patch(org.owner(), id, "{\"email\": {\"recipients\": [\"a@example.com\"]}}", null))
                .hasStatus(400);
        assertThat(patch(
                        org.owner(),
                        id,
                        "{\"webhook\": {\"url\": \"http://" + TestHostResolver.PUBLIC_HOST + "\"}}",
                        null))
                .hasStatus(422);
    }

    @Test
    void replacesTheRecipientsOfAnEmailChannel() {
        Organization org = newOrganization();
        String id = read(create(org.owner(), org.id(), emailBody("Guardia")), "$.id");

        assertThat(patch(org.owner(), id, "{\"email\": {\"recipients\": [\"luis@example.com\"]}}", null))
                .hasStatus(200)
                .bodyJson()
                .extractingPath("$.email.recipients")
                .isEqualTo(List.of("l***@example.com"));
        assertThat(unsealed(id)).isEqualTo(new ChannelConfig.Email(List.of("luis@example.com")));
    }

    @Test
    void anEmailChannelHasNoSecretToRotate() {
        Organization org = newOrganization();
        String id = read(create(org.owner(), org.id(), emailBody("Guardia")), "$.id");

        assertThat(as(org.owner(), mvc.post().uri("/api/v1/notification-channels/" + id + "/rotate-secret"))
                        .exchange())
                .hasStatus(409);
    }

    @Test
    void deletesAChannel() {
        Organization org = newOrganization();
        String id = read(create(org.owner(), org.id(), emailBody("Guardia")), "$.id");

        assertThat(as(org.owner(), mvc.delete().uri("/api/v1/notification-channels/" + id))
                        .exchange())
                .hasStatus(204);

        assertThat(get(org.owner(), id)).hasStatus(404);
    }

    /** A member reads the channels, masked; changing them is for owners and admins. */
    @Test
    void aMemberReadsTheChannelsButCannotChangeThem() {
        Organization org = newOrganization();
        String id = read(create(org.owner(), org.id(), webhookBody(HOOK)), "$.id");
        Caller member = memberOf(org.id(), Role.MEMBER);

        assertThat(get(member, id)).hasStatus(200);
        assertThat(patch(member, id, "{\"name\": \"Mine\"}", null)).hasStatus(403);
    }

    /** Someone from another organization gets exactly what a missing channel gets. */
    @Test
    void someoneFromAnotherOrganizationGets404AsForAMissingChannel() {
        Organization org = newOrganization();
        String id = read(create(org.owner(), org.id(), webhookBody(HOOK)), "$.id");
        Caller mallory = newOrganization().owner();
        String missing = UUID.randomUUID().toString();

        for (String target : List.of(id, missing)) {
            for (MvcTestResult attempt : List.of(
                    get(mallory, target),
                    patch(mallory, target, "{\"name\": \"Mine\"}", null),
                    as(mallory, mvc.delete().uri("/api/v1/notification-channels/" + target))
                            .exchange(),
                    as(mallory, mvc.post().uri("/api/v1/notification-channels/" + target + "/rotate-secret"))
                            .exchange())) {
                assertThat(attempt)
                        .hasStatus(404)
                        .bodyJson()
                        .extractingPath("$.detail")
                        .isEqualTo("notification channel " + target + " was not found");
            }
        }
        assertThat(list(mallory, org.id())).hasStatus(404);
        assertThat(get(org.owner(), id)).hasStatus(200);
    }

    /** Deleting a project deletes the channels limited to it; those of every project stay. */
    @Test
    void deletingAProjectDeletesItsChannels() {
        Organization org = newOrganization();
        UUID project = newProject(org.id());
        String limited = read(create(org.owner(), org.id(), """
                        {"name": "Production", "type": "EMAIL", "projectId": "%s",
                         "email": {"recipients": ["a@example.com"]}}""".formatted(project)), "$.id");
        String everywhere = read(create(org.owner(), org.id(), emailBody("Everything")), "$.id");

        projects.delete(UUID.fromString(org.owner().id()), project);

        Awaitility.await()
                .atMost(CLEANUP)
                .untilAsserted(() -> assertThat(channels.existsById(UUID.fromString(limited)))
                        .isFalse());
        assertThat(channels.existsById(UUID.fromString(everywhere))).isTrue();
    }

    @Test
    void deletingAnOrganizationDeletesAllItsChannels() {
        Organization org = newOrganization();
        create(org.owner(), org.id(), emailBody("Guardia"));
        create(org.owner(), org.id(), webhookBody(HOOK));

        organizations.delete(UUID.fromString(org.owner().id()), org.id());

        Awaitility.await()
                .atMost(CLEANUP)
                .untilAsserted(() ->
                        assertThat(channels.countByOrganizationId(org.id())).isZero());
    }

    /** The associated data ties a ciphertext to its channel: copied to another one, it does not decrypt. */
    @Test
    void aConfigurationMovedToAnotherChannelDoesNotDecrypt() {
        Organization org = newOrganization();
        String first = read(create(org.owner(), org.id(), webhookBody(HOOK)), "$.id");
        String second = read(create(org.owner(), org.id(), webhookBody(HOOK)), "$.id");
        jdbc.update(
                "UPDATE notification_channels SET config_ciphertext = ? WHERE id = ?",
                ciphertextOf(first),
                UUID.fromString(second));

        Assertions.assertThatThrownBy(() -> unsealed(second)).isInstanceOf(DecryptionFailedException.class);
    }

    @Test
    void isDocumentedInOpenApiWithItsErrors() {
        MvcTestResult result = mvc.get().uri("/v3/api-docs").exchange();

        assertThat(result)
                .bodyJson()
                .extractingPath("$.paths['/api/v1/organizations/{orgId}/notification-channels'].post.responses")
                .asMap()
                .containsKeys("201", "400", "401", "403", "404", "422");
        assertThat(result)
                .bodyJson()
                .extractingPath("$.paths['/api/v1/notification-channels/{channelId}'].patch.responses")
                .asMap()
                .containsKeys("200", "400", "401", "403", "404", "409", "412", "422");
    }

    private ChannelConfig unsealed(String channel) {
        return transactions.execute(
                tx -> configs.unseal(channels.findById(UUID.fromString(channel)).orElseThrow()));
    }

    private byte[] ciphertextOf(String channel) {
        return jdbc.queryForObject(
                "SELECT config_ciphertext FROM notification_channels WHERE id = ?",
                byte[].class,
                UUID.fromString(channel));
    }

    private static String emailBody(String name) {
        return """
                {"name": "%s", "type": "EMAIL", "email": {"recipients": ["oncall@example.com"]}}""".formatted(name);
    }

    private static String webhookBody(String url) {
        return """
                {"name": "Slack bridge", "type": "WEBHOOK", "webhook": {"url": "%s"}}""".formatted(url);
    }

    private MvcTestResult create(Caller caller, UUID organization, String body) {
        return as(caller, json(mvc.post(), "/api/v1/organizations/" + organization + "/notification-channels", body))
                .exchange();
    }

    private MvcTestResult list(Caller caller, UUID organization) {
        return as(caller, mvc.get().uri("/api/v1/organizations/" + organization + "/notification-channels"))
                .exchange();
    }

    private MvcTestResult get(Caller caller, String channel) {
        return as(caller, mvc.get().uri("/api/v1/notification-channels/" + channel))
                .exchange();
    }

    private MvcTestResult patch(Caller caller, String channel, String body, @Nullable String ifMatch) {
        MockMvcRequestBuilder request = as(caller, json(mvc.patch(), "/api/v1/notification-channels/" + channel, body));
        if (ifMatch != null) {
            request.header(HttpHeaders.IF_MATCH, ifMatch);
        }
        return request.exchange();
    }

    private static MockMvcRequestBuilder json(MockMvcRequestBuilder request, String uri, String body) {
        return request.uri(uri).contentType(MediaType.APPLICATION_JSON).content(body);
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

    /** A signed-in user: the id and an access token. */
    private record Caller(String id, String token) {}

    private record Organization(UUID id, Caller owner) {}

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
        return new Caller(user.toString(), tokens.issue(user).value());
    }

    private UUID newProject(UUID organization) {
        UUID project = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO projects (id, organization_id, name, created_at, updated_at)
                VALUES (?, ?, ?, now(), now())""", project, organization, "Project " + project);
        return project;
    }
}
