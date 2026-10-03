package io.github.ricardoord.opswatch.organization.web;

import static io.github.ricardoord.opswatch.identity.domain.UserBuilder.uniqueEmail;
import static org.assertj.core.api.Assertions.assertThat;

import com.jayway.jsonpath.JsonPath;
import io.github.ricardoord.opswatch.IntegrationTest;
import io.github.ricardoord.opswatch.organization.ProjectDeleted;
import io.github.ricardoord.opswatch.organization.Role;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

/** {@code /api/v1/organizations/{orgId}/projects} and {@code /api/v1/projects}, through the real security chain. */
@IntegrationTest
@RecordApplicationEvents
class ProjectApiIT {

    private static final String PASSWORD = "correct horse battery";

    @Autowired
    private MockMvcTester mvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ApplicationEvents events;

    @Test
    void anAdminCreatesAProjectThatAnyMemberReads() {
        Caller owner = signedIn();
        String organization = organizationOf(owner);
        Caller admin = memberOf(organization, Role.ADMIN);
        Caller viewer = memberOf(organization, Role.VIEWER);

        MvcTestResult created = create(admin, organization, """
                {"name": "  Production ", "description": " Live services "}""");

        assertThat(created).hasStatus(201);
        String id = read(created, "$.id");
        assertThat(created).headers().hasValue(HttpHeaders.LOCATION, "/api/v1/projects/" + id);
        assertThat(created).headers().hasValue(HttpHeaders.ETAG, "\"0\"");
        assertThat(created).bodyJson().extractingPath("$.organizationId").isEqualTo(organization);
        assertThat(created).bodyJson().extractingPath("$.name").isEqualTo("Production");
        assertThat(created).bodyJson().extractingPath("$.description").isEqualTo("Live services");
        assertThat(created).bodyJson().extractingPath("$.version").isEqualTo(0);
        assertThat(get(viewer, id)).hasStatus(200).headers().hasValue(HttpHeaders.ETAG, "\"0\"");
        assertThat(list(viewer, organization, ""))
                .bodyJson()
                .extractingPath("$.items[*].name")
                .isEqualTo(List.of("Production"));
    }

    @Test
    void theRoleDecidesWhatAMemberCanDo() {
        Caller owner = signedIn();
        String organization = organizationOf(owner);
        String id = projectOf(owner, organization, "Production");
        Caller admin = memberOf(organization, Role.ADMIN);
        Caller member = memberOf(organization, Role.MEMBER);
        Caller viewer = memberOf(organization, Role.VIEWER);

        for (Caller notAllowed : List.of(member, viewer)) {
            assertThat(create(notAllowed, organization, "{\"name\": \"Staging\"}"))
                    .hasStatus(403)
                    .bodyJson()
                    .extractingPath("$.code")
                    .isEqualTo("access-denied");
            assertThat(patch(notAllowed, id, "{\"name\": \"Renamed\"}", null)).hasStatus(403);
            assertThat(delete(notAllowed, id)).hasStatus(403);
            assertThat(get(notAllowed, id)).hasStatus(200);
        }
        assertThat(patch(admin, id, "{\"name\": \"Renamed by an admin\"}", null))
                .hasStatus(200);
        assertThat(get(viewer, id)).bodyJson().extractingPath("$.name").isEqualTo("Renamed by an admin");
        assertThat(delete(admin, id)).hasStatus(204);
    }

    @Test
    void someoneFromAnotherOrganizationGets404AsForAMissingProjectAndChangesNothing() {
        Caller ana = signedIn();
        String organization = organizationOf(ana);
        String id = projectOf(ana, organization, "Production");
        Caller mallory = signedIn();
        organizationOf(mallory);
        String missing = UUID.randomUUID().toString();

        // Exactly what a missing project gets, with a detail that does not name its organization
        for (String target : List.of(id, missing)) {
            assertThat(get(mallory, target))
                    .hasStatus(404)
                    .bodyJson()
                    .extractingPath("$.detail")
                    .isEqualTo("project " + target + " was not found");
            assertThat(patch(mallory, target, "{\"name\": \"Stolen\"}", null)).hasStatus(404);
            assertThat(delete(mallory, target)).hasStatus(404);
        }
        assertThat(list(mallory, organization, "")).hasStatus(404);
        assertThat(create(mallory, organization, "{\"name\": \"Planted\"}")).hasStatus(404);

        assertThat(get(ana, id))
                .hasStatus(200)
                .bodyJson()
                .extractingPath("$.name")
                .isEqualTo("Production");
        assertThat(list(ana, organization, ""))
                .bodyJson()
                .extractingPath("$.page.totalElements")
                .isEqualTo(1);
    }

    @Test
    void theOrganizationComesFromThePathNeverFromTheBody() {
        Caller ana = signedIn();
        String organization = organizationOf(ana);
        String another = organizationOf(ana);

        MvcTestResult planted = create(ana, organization, """
                {"name": "Production", "organizationId": "%s"}""".formatted(another));

        assertThat(planted).hasStatus(400).bodyJson().extractingPath("$.code").isEqualTo("malformed-request");
        assertThat(list(ana, another, ""))
                .bodyJson()
                .extractingPath("$.page.totalElements")
                .isEqualTo(0);
    }

    @Test
    void aNameIsUniqueInTheOrganizationWhateverItsCaseAmongTheProjectsNotDeleted() {
        Caller ana = signedIn();
        String organization = organizationOf(ana);
        String production = projectOf(ana, organization, "Production");
        String staging = projectOf(ana, organization, "Staging");

        assertThat(create(ana, organization, "{\"name\": \"PRODUCTION\"}"))
                .hasStatus(409)
                .bodyJson()
                .extractingPath("$.code")
                .isEqualTo("conflict");
        assertThat(patch(ana, staging, "{\"name\": \"production\"}", null)).hasStatus(409);
        assertThat(get(ana, staging)).bodyJson().extractingPath("$.name").isEqualTo("Staging");

        // Another organization, or after deleting the first one
        assertThat(create(ana, organizationOf(ana), "{\"name\": \"Production\"}"))
                .hasStatus(201);
        assertThat(delete(ana, production)).hasStatus(204);
        assertThat(create(ana, organization, "{\"name\": \"Production\"}")).hasStatus(201);
    }

    @Test
    void theTwentyFirstProjectIsOverTheQuotaUntilOneIsDeleted() {
        Caller ana = signedIn();
        String organization = organizationOf(ana);
        List<String> ids = new ArrayList<>();
        for (int i = 1; i <= 20; i++) {
            ids.add(projectOf(ana, organization, "Project " + i));
        }

        assertThat(create(ana, organization, "{\"name\": \"Project 21\"}"))
                .hasStatus(422)
                .bodyJson()
                .extractingPath("$.code")
                .isEqualTo("quota-exceeded");
        assertThat(delete(ana, ids.getFirst())).hasStatus(204);
        assertThat(create(ana, organization, "{\"name\": \"Project 21\"}")).hasStatus(201);
    }

    @Test
    void aPatchOnlyChangesWhatItSendsAndANullDescriptionRemovesIt() {
        Caller ana = signedIn();
        String organization = organizationOf(ana);
        String id = read(create(ana, organization, "{\"name\": \"Production\", \"description\": \"Live\"}"), "$.id");

        MvcTestResult empty = patch(ana, id, "{}", null);
        assertThat(empty).hasStatus(200).headers().hasValue(HttpHeaders.ETAG, "\"0\"");
        assertThat(empty).bodyJson().extractingPath("$.description").isEqualTo("Live");

        MvcTestResult renamed = patch(ana, id, "{\"name\": \"Prod\"}", null);
        assertThat(renamed).hasStatus(200).headers().hasValue(HttpHeaders.ETAG, "\"1\"");
        assertThat(renamed).bodyJson().extractingPath("$.description").isEqualTo("Live");

        MvcTestResult cleared = patch(ana, id, "{\"description\": null}", null);
        assertThat(cleared).hasStatus(200);
        assertThat(cleared).bodyJson().extractingPath("$.description").isNull();
        assertThat(cleared).bodyJson().extractingPath("$.name").isEqualTo("Prod");

        assertThat(patch(ana, id, "{\"name\": null}", null)).hasStatus(400);
        assertThat(patch(ana, id, "{\"description\": \"%s\"}".formatted("d".repeat(501)), null))
                .hasStatus(400)
                .bodyJson()
                .extractingPath("$.errors[0].field")
                .isEqualTo("description");
        assertThat(patch(ana, id, "{\"organizationId\": \"%s\"}".formatted(UUID.randomUUID()), null))
                .hasStatus(400)
                .bodyJson()
                .extractingPath("$.code")
                .isEqualTo("malformed-request");
    }

    @Test
    void aStaleIfMatchIsAFailedPreconditionAndChangesNothing() {
        Caller ana = signedIn();
        String id = projectOf(ana, organizationOf(ana), "Production");

        assertThat(patch(ana, id, "{\"name\": \"Prod\"}", "\"0\""))
                .hasStatus(200)
                .headers()
                .hasValue(HttpHeaders.ETAG, "\"1\"");

        assertThat(patch(ana, id, "{\"name\": \"Lost update\"}", "\"0\""))
                .hasStatus(412)
                .bodyJson()
                .extractingPath("$.code")
                .isEqualTo("precondition-failed");
        assertThat(get(ana, id)).bodyJson().extractingPath("$.name").isEqualTo("Prod");
    }

    @Test
    void aDeletedProjectIsGoneAndItsDeletionIsPublished() {
        Caller ana = signedIn();
        String organization = organizationOf(ana);
        String id = projectOf(ana, organization, "Production");

        assertThat(delete(ana, id)).hasStatus(204);

        assertThat(get(ana, id)).hasStatus(404);
        assertThat(delete(ana, id)).hasStatus(404);
        assertThat(list(ana, organization, ""))
                .bodyJson()
                .extractingPath("$.page.totalElements")
                .isEqualTo(0);
        assertThat(events.stream(ProjectDeleted.class)).singleElement().satisfies(event -> {
            assertThat(event.projectId()).hasToString(id);
            assertThat(event.organizationId()).hasToString(organization);
            assertThat(event.deletedBy()).hasToString(ana.id());
        });
    }

    @Test
    void deletingTheOrganizationDeletesItsProjectsAndPublishesEachDeletion() {
        Caller owner = signedIn();
        String organization = organizationOf(owner);
        Caller viewer = memberOf(organization, Role.VIEWER);
        String production = projectOf(owner, organization, "Production");
        String staging = projectOf(owner, organization, "Staging");

        assertThat(mvc.delete()
                        .uri("/api/v1/organizations/" + organization)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + owner.token())
                        .exchange())
                .hasStatus(204);

        for (String id : List.of(production, staging)) {
            assertThat(get(owner, id)).hasStatus(404);
            assertThat(get(viewer, id)).hasStatus(404);
        }
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM projects WHERE organization_id = ? AND deleted_at IS NULL",
                        Long.class,
                        UUID.fromString(organization)))
                .isZero();
        assertThat(events.stream(ProjectDeleted.class))
                .extracting(event -> event.projectId().toString())
                .containsExactlyInAnyOrder(production, staging);
        assertThat(events.stream(ProjectDeleted.class))
                .allSatisfy(event -> assertThat(event.deletedBy()).hasToString(owner.id()));
    }

    @Test
    void listsAPageSortedByNameByDefaultAndRejectsOtherSorts() {
        Caller ana = signedIn();
        String organization = organizationOf(ana);
        projectOf(ana, organization, "Charlie");
        projectOf(ana, organization, "Alpha");
        projectOf(ana, organization, "Bravo");

        assertThat(list(ana, organization, "?size=2"))
                .bodyJson()
                .extractingPath("$.items[*].name")
                .isEqualTo(List.of("Alpha", "Bravo"));
        assertThat(list(ana, organization, "?sort=createdAt,desc"))
                .bodyJson()
                .extractingPath("$.items[*].name")
                .isEqualTo(List.of("Bravo", "Alpha", "Charlie"));
        for (String query : List.of("?size=101", "?sort=version", "?sort=organizationId")) {
            assertThat(list(ana, organization, query))
                    .hasStatus(400)
                    .bodyJson()
                    .extractingPath("$.code")
                    .isEqualTo("invalid-parameter");
        }
        assertThat(get(ana, "not-a-uuid")).hasStatus(400);
    }

    @Test
    void isDocumentedInOpenApiWithItsErrors() {
        MvcTestResult result = mvc.get().uri("/v3/api-docs").exchange();

        assertThat(result)
                .bodyJson()
                .extractingPath("$.paths['/api/v1/organizations/{orgId}/projects'].post.responses")
                .asMap()
                .containsKeys("201", "400", "401", "403", "404", "409", "422");
        assertThat(result)
                .bodyJson()
                .extractingPath("$.paths['/api/v1/projects/{projectId}'].patch.responses")
                .asMap()
                .containsKeys("200", "400", "401", "403", "404", "409", "412");
        assertThat(result)
                .bodyJson()
                .extractingPath("$.components.schemas.UpdateProjectRequest.properties.description.maxLength")
                .isEqualTo(500);
    }

    /** A signed-in user: the access token and the id. */
    private record Caller(String token, String id) {}

    private Caller signedIn() {
        String email = uniqueEmail();
        MvcTestResult registration = mvc.post()
                .uri("/api/v1/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"email": "%s", "displayName": "Ana", "password": "%s"}
                        """.formatted(email, PASSWORD))
                .exchange();
        assertThat(registration).hasStatus(201);
        MvcTestResult login = mvc.post()
                .uri("/api/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"email": "%s", "password": "%s"}
                        """.formatted(email, PASSWORD))
                .exchange();
        assertThat(login).hasStatus(200);
        return new Caller(read(login, "$.accessToken"), read(registration, "$.id"));
    }

    private String organizationOf(Caller caller) {
        MvcTestResult result = mvc.post()
                .uri("/api/v1/organizations")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + caller.token())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\": \"CharityLink\"}")
                .exchange();
        assertThat(result).hasStatus(201);
        return read(result, "$.id");
    }

    /** Straight into the table: the member endpoints are tested elsewhere. */
    private Caller memberOf(String organizationId, Role role) {
        Caller caller = signedIn();
        jdbc.update("""
                INSERT INTO memberships (organization_id, user_id, role, created_at, updated_at)
                VALUES (?, ?, ?, now(), now())""", UUID.fromString(organizationId), UUID.fromString(caller.id()), role.name());
        return caller;
    }

    private String projectOf(Caller caller, String organizationId, String name) {
        MvcTestResult result = create(caller, organizationId, "{\"name\": \"%s\"}".formatted(name));
        assertThat(result).hasStatus(201);
        return read(result, "$.id");
    }

    private MvcTestResult create(Caller caller, String organizationId, String body) {
        return mvc.post()
                .uri("/api/v1/organizations/" + organizationId + "/projects")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + caller.token())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body)
                .exchange();
    }

    private MvcTestResult list(Caller caller, String organizationId, String query) {
        return mvc.get()
                .uri("/api/v1/organizations/" + organizationId + "/projects" + query)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + caller.token())
                .exchange();
    }

    private MvcTestResult get(Caller caller, String id) {
        return mvc.get()
                .uri("/api/v1/projects/" + id)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + caller.token())
                .exchange();
    }

    private MvcTestResult patch(Caller caller, String id, String body, @Nullable String ifMatch) {
        var request = mvc.patch()
                .uri("/api/v1/projects/" + id)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + caller.token())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body);
        if (ifMatch != null) {
            request.header(HttpHeaders.IF_MATCH, ifMatch);
        }
        return request.exchange();
    }

    private MvcTestResult delete(Caller caller, String id) {
        return mvc.delete()
                .uri("/api/v1/projects/" + id)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + caller.token())
                .exchange();
    }

    private static String read(MvcTestResult result, String path) {
        try {
            return JsonPath.read(result.getResponse().getContentAsString(), path);
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }
}
