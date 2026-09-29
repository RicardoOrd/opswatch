package io.github.ricardoord.opswatch.organization.web;

import static io.github.ricardoord.opswatch.identity.domain.UserBuilder.uniqueEmail;
import static org.assertj.core.api.Assertions.assertThat;

import com.jayway.jsonpath.JsonPath;
import io.github.ricardoord.opswatch.IntegrationTest;
import io.github.ricardoord.opswatch.organization.OrganizationDeleted;
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

/** {@code /api/v1/organizations} through the real security chain, against PostgreSQL. */
@IntegrationTest
@RecordApplicationEvents
class OrganizationApiIT {

    private static final String PASSWORD = "correct horse battery";

    @Autowired
    private MockMvcTester mvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ApplicationEvents events;

    @Test
    void theCreatorBecomesItsOwner() {
        Caller ana = signedIn();

        MvcTestResult result = create(ana, "  CharityLink ");

        assertThat(result).hasStatus(201);
        String id = read(result, "$.id");
        assertThat(result).headers().hasValue(HttpHeaders.LOCATION, "/api/v1/organizations/" + id);
        assertThat(result).headers().hasValue(HttpHeaders.ETAG, "\"0\"");
        assertThat(result).bodyJson().extractingPath("$.name").isEqualTo("CharityLink");
        assertThat(result).bodyJson().extractingPath("$.myRole").isEqualTo("OWNER");
        assertThat(result).bodyJson().extractingPath("$.version").isEqualTo(0);
        assertThat(list(ana, "")).bodyJson().extractingPath("$.items[0].myRole").isEqualTo("OWNER");
        assertThat(get(ana, id)).hasStatus(200).headers().hasValue(HttpHeaders.ETAG, "\"0\"");
    }

    @Test
    void someoneWhoIsNotAMemberGets404ForEverythingAndChangesNothing() {
        Caller ana = signedIn();
        Caller mallory = signedIn();
        String id = organizationOf(ana, "CharityLink");
        String missing = UUID.randomUUID().toString();

        // Exactly what a missing organization gets: nothing tells that this one exists
        for (String target : List.of(id, missing)) {
            assertThat(get(mallory, target)).hasStatus(404);
            assertThat(patch(mallory, target, "{\"name\": \"Stolen\"}", null)).hasStatus(404);
            assertThat(delete(mallory, target)).hasStatus(404);
        }
        assertThat(get(mallory, id)).bodyJson().extractingPath("$.code").isEqualTo("resource-not-found");
        assertThat(get(mallory, id))
                .bodyJson()
                .extractingPath("$.detail")
                .isEqualTo("organization " + id + " was not found");

        assertThat(get(ana, id))
                .hasStatus(200)
                .bodyJson()
                .extractingPath("$.name")
                .isEqualTo("CharityLink");
        assertThat(list(mallory, ""))
                .bodyJson()
                .extractingPath("$.page.totalElements")
                .isEqualTo(0);
    }

    @Test
    void theRoleDecidesWhatAMemberCanDo() {
        Caller owner = signedIn();
        String id = organizationOf(owner, "CharityLink");
        Caller admin = memberOf(id, Role.ADMIN);
        Caller member = memberOf(id, Role.MEMBER);
        Caller viewer = memberOf(id, Role.VIEWER);

        for (Caller anyone : List.of(owner, admin, member, viewer)) {
            assertThat(get(anyone, id)).hasStatus(200);
        }
        assertThat(get(viewer, id)).bodyJson().extractingPath("$.myRole").isEqualTo("VIEWER");

        assertThat(patch(viewer, id, "{\"name\": \"Viewer\"}", null))
                .hasStatus(403)
                .bodyJson()
                .extractingPath("$.code")
                .isEqualTo("access-denied");
        assertThat(patch(member, id, "{\"name\": \"Member\"}", null)).hasStatus(403);
        assertThat(patch(admin, id, "{\"name\": \"Renamed by an admin\"}", null))
                .hasStatus(200);

        assertThat(delete(viewer, id)).hasStatus(403);
        assertThat(delete(member, id)).hasStatus(403);
        assertThat(delete(admin, id)).hasStatus(403);
        assertThat(get(owner, id)).bodyJson().extractingPath("$.name").isEqualTo("Renamed by an admin");
        assertThat(delete(owner, id)).hasStatus(204);
    }

    @Test
    void aStaleIfMatchIsAFailedPreconditionAndChangesNothing() {
        Caller ana = signedIn();
        String id = organizationOf(ana, "CharityLink");

        MvcTestResult renamed = patch(ana, id, "{\"name\": \"Charity Link\"}", "\"0\"");
        assertThat(renamed).hasStatus(200).headers().hasValue(HttpHeaders.ETAG, "\"1\"");
        assertThat(renamed).bodyJson().extractingPath("$.version").isEqualTo(1);

        MvcTestResult stale = patch(ana, id, "{\"name\": \"Lost update\"}", "\"0\"");

        assertThat(stale).hasStatus(412).bodyJson().extractingPath("$.code").isEqualTo("precondition-failed");
        assertThat(get(ana, id)).bodyJson().extractingPath("$.name").isEqualTo("Charity Link");
        // Without If-Match, the change goes through: the header is optional
        assertThat(patch(ana, id, "{\"name\": \"Third\"}", null)).hasStatus(200);
    }

    @Test
    void aPatchOnlyChangesWhatItSends() {
        Caller ana = signedIn();
        String id = organizationOf(ana, "CharityLink");

        MvcTestResult empty = patch(ana, id, "{}", null);
        assertThat(empty).hasStatus(200).headers().hasValue(HttpHeaders.ETAG, "\"0\"");
        assertThat(empty).bodyJson().extractingPath("$.name").isEqualTo("CharityLink");

        assertThat(patch(ana, id, "{\"name\": null}", null)).hasStatus(400);
        assertThat(patch(ana, id, "{\"name\": \"   \"}", null))
                .hasStatus(400)
                .bodyJson()
                .extractingPath("$.errors[0].field")
                .isEqualTo("name");
        assertThat(patch(ana, id, "{\"name\": \"CharityLink\", \"id\": \"%s\"}".formatted(UUID.randomUUID()), null))
                .hasStatus(400)
                .bodyJson()
                .extractingPath("$.code")
                .isEqualTo("malformed-request");
    }

    @Test
    void aDeletedOrganizationIsGoneForAllItsMembers() {
        Caller owner = signedIn();
        String id = organizationOf(owner, "CharityLink");
        Caller viewer = memberOf(id, Role.VIEWER);

        assertThat(delete(owner, id)).hasStatus(204);

        assertThat(get(owner, id)).hasStatus(404);
        assertThat(get(viewer, id)).hasStatus(404);
        assertThat(delete(owner, id)).hasStatus(404);
        assertThat(list(owner, ""))
                .bodyJson()
                .extractingPath("$.page.totalElements")
                .isEqualTo(0);
        assertThat(list(viewer, ""))
                .bodyJson()
                .extractingPath("$.page.totalElements")
                .isEqualTo(0);
        assertThat(events.stream(OrganizationDeleted.class))
                .anyMatch(event -> event.organizationId().toString().equals(id));
    }

    @Test
    void theSixthOrganizationOfAnOwnerIsOverTheQuotaUntilOneIsDeleted() {
        Caller ana = signedIn();
        List<String> ids = new ArrayList<>();
        for (int i = 1; i <= 5; i++) {
            ids.add(organizationOf(ana, "Organization " + i));
        }
        // Being a member of someone else's organization does not count
        memberOf(organizationOf(signedIn(), "Another"), Role.ADMIN);

        MvcTestResult sixth = create(ana, "Organization 6");

        assertThat(sixth).hasStatus(422).bodyJson().extractingPath("$.code").isEqualTo("quota-exceeded");
        assertThat(delete(ana, ids.getFirst())).hasStatus(204);
        assertThat(create(ana, "Organization 6")).hasStatus(201);
    }

    @Test
    void listsAPageSortedByNameByDefault() {
        Caller ana = signedIn();
        organizationOf(ana, "Charlie");
        organizationOf(ana, "Alpha");
        organizationOf(ana, "Bravo");

        MvcTestResult firstPage = list(ana, "?size=2");

        assertThat(firstPage).hasStatus(200);
        assertThat(firstPage).bodyJson().extractingPath("$.items[*].name").isEqualTo(List.of("Alpha", "Bravo"));
        assertThat(firstPage).bodyJson().extractingPath("$.page.totalElements").isEqualTo(3);
        assertThat(firstPage).bodyJson().extractingPath("$.page.totalPages").isEqualTo(2);
        assertThat(list(ana, "?size=2&page=1"))
                .bodyJson()
                .extractingPath("$.items[*].name")
                .isEqualTo(List.of("Charlie"));
        assertThat(list(ana, "?sort=createdAt,desc"))
                .bodyJson()
                .extractingPath("$.items[*].name")
                .isEqualTo(List.of("Bravo", "Alpha", "Charlie"));
    }

    @Test
    void rejectsPagesAndSortsTheListDoesNotOffer() {
        Caller ana = signedIn();

        for (String query : List.of("?size=101", "?page=-1", "?sort=version", "?sort=name,sideways")) {
            assertThat(list(ana, query))
                    .hasStatus(400)
                    .bodyJson()
                    .extractingPath("$.code")
                    .isEqualTo("invalid-parameter");
        }
        assertThat(get(ana, "not-a-uuid")).hasStatus(400);
    }

    @Test
    void needsAnAccessToken() {
        assertThat(mvc.get().uri("/api/v1/organizations").exchange()).hasStatus(401);
        assertThat(mvc.post()
                        .uri("/api/v1/organizations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\": \"CharityLink\"}")
                        .exchange())
                .hasStatus(401);
    }

    @Test
    void isDocumentedInOpenApiWithItsErrors() {
        MvcTestResult result = mvc.get().uri("/v3/api-docs").exchange();

        assertThat(result)
                .bodyJson()
                .extractingPath("$.paths['/api/v1/organizations'].post.responses")
                .asMap()
                .containsKeys("201", "400", "401", "422");
        assertThat(result)
                .bodyJson()
                .extractingPath("$.paths['/api/v1/organizations'].get.parameters[*].name")
                .isEqualTo(List.of("page", "size", "sort"));
        assertThat(result)
                .bodyJson()
                .extractingPath("$.paths['/api/v1/organizations/{orgId}'].patch.responses")
                .asMap()
                .containsKeys("200", "400", "401", "403", "404", "409", "412");
        assertThat(result)
                .bodyJson()
                .extractingPath("$.paths['/api/v1/organizations/{orgId}'].delete.responses")
                .asMap()
                .containsKeys("204", "401", "403", "404");
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

    /** Memberships other than the creator's arrive with OW-017: until then, straight into the table. */
    private Caller memberOf(String organizationId, Role role) {
        Caller caller = signedIn();
        jdbc.update("""
                INSERT INTO memberships (organization_id, user_id, role, created_at, updated_at)
                VALUES (?, ?, ?, now(), now())""", UUID.fromString(organizationId), UUID.fromString(caller.id()), role.name());
        return caller;
    }

    private String organizationOf(Caller caller, String name) {
        MvcTestResult result = create(caller, name);
        assertThat(result).hasStatus(201);
        return read(result, "$.id");
    }

    private MvcTestResult create(Caller caller, String name) {
        return mvc.post()
                .uri("/api/v1/organizations")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + caller.token())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\": \"%s\"}".formatted(name))
                .exchange();
    }

    private MvcTestResult list(Caller caller, String query) {
        return mvc.get()
                .uri("/api/v1/organizations" + query)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + caller.token())
                .exchange();
    }

    private MvcTestResult get(Caller caller, String id) {
        return mvc.get()
                .uri("/api/v1/organizations/" + id)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + caller.token())
                .exchange();
    }

    private MvcTestResult patch(Caller caller, String id, String body, @Nullable String ifMatch) {
        var request = mvc.patch()
                .uri("/api/v1/organizations/" + id)
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
                .uri("/api/v1/organizations/" + id)
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
