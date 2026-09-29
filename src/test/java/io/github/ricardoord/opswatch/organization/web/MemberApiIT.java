package io.github.ricardoord.opswatch.organization.web;

import static io.github.ricardoord.opswatch.identity.domain.UserBuilder.uniqueEmail;
import static org.assertj.core.api.Assertions.assertThat;

import com.jayway.jsonpath.JsonPath;
import io.github.ricardoord.opswatch.IntegrationTest;
import io.github.ricardoord.opswatch.organization.Role;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

/** {@code /api/v1/organizations/{orgId}/members} through the real security chain, against PostgreSQL. */
@IntegrationTest
class MemberApiIT {

    private static final String PASSWORD = "correct horse battery";

    @Autowired
    private MockMvcTester mvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void anOwnerAddsSomeoneWithAnAccountByEmail() {
        Caller owner = signedIn("Ana");
        Caller luis = signedIn("Luis");
        String org = organizationOf(owner);

        MvcTestResult result = add(owner, org, "  " + luis.email().toUpperCase(Locale.ROOT) + " ", "VIEWER");

        assertThat(result).hasStatus(201);
        assertThat(result)
                .headers()
                .hasValue(HttpHeaders.LOCATION, "/api/v1/organizations/" + org + "/members/" + luis.id())
                .hasValue(HttpHeaders.ETAG, "\"0\"");
        assertThat(result).bodyJson().extractingPath("$.userId").isEqualTo(luis.id());
        assertThat(result).bodyJson().extractingPath("$.email").isEqualTo(luis.email());
        assertThat(result).bodyJson().extractingPath("$.displayName").isEqualTo("Luis");
        assertThat(result).bodyJson().extractingPath("$.role").isEqualTo("VIEWER");
        // The new member sees the organization, with their role
        assertThat(getOrganization(luis, org))
                .bodyJson()
                .extractingPath("$.myRole")
                .isEqualTo("VIEWER");
    }

    @Test
    void everyMemberListsTheMembersInTheOrderTheyJoined() {
        Caller owner = signedIn("Ana");
        String org = organizationOf(owner);
        Caller viewer = memberOf(owner, org, Role.VIEWER);
        memberOf(owner, org, Role.MEMBER);

        MvcTestResult result = list(viewer, org, "");

        assertThat(result).hasStatus(200);
        assertThat(result).bodyJson().extractingPath("$.page.totalElements").isEqualTo(3);
        assertThat(result).bodyJson().extractingPath("$.items[*].role").isEqualTo(List.of("OWNER", "VIEWER", "MEMBER"));
        assertThat(result).bodyJson().extractingPath("$.items[0].email").isEqualTo(owner.email());
        assertThat(list(viewer, org, "?sort=joinedAt,desc&size=1"))
                .bodyJson()
                .extractingPath("$.items[*].role")
                .isEqualTo(List.of("MEMBER"));
        assertThat(list(viewer, org, "?sort=email")).hasStatus(400);
    }

    @Test
    void anAdminManagesMembersAndViewersButNotAdminsOrOwners() {
        Caller owner = signedIn("Ana");
        String org = organizationOf(owner);
        Caller admin = memberOf(owner, org, Role.ADMIN);
        Caller otherAdmin = memberOf(owner, org, Role.ADMIN);
        // With a second OWNER, the last-OWNER rule stays out of the way and the answer is the permission
        memberOf(owner, org, Role.OWNER);

        // Acceptance: an ADMIN cannot assign ADMIN
        assertThat(add(admin, org, signedIn("Luis").email(), "ADMIN"))
                .hasStatus(403)
                .bodyJson()
                .extractingPath("$.code")
                .isEqualTo("access-denied");
        assertThat(add(admin, org, signedIn("Luis").email(), "OWNER")).hasStatus(403);
        assertThat(patch(admin, org, otherAdmin.id(), "MEMBER", null)).hasStatus(403);
        assertThat(patch(admin, org, owner.id(), "VIEWER", null)).hasStatus(403);
        assertThat(remove(admin, org, otherAdmin.id())).hasStatus(403);
        assertThat(remove(admin, org, owner.id())).hasStatus(403);

        Caller member = signedIn("Luis");
        assertThat(add(admin, org, member.email(), "MEMBER")).hasStatus(201);
        assertThat(patch(admin, org, member.id(), "VIEWER", null)).hasStatus(200);
        assertThat(remove(admin, org, member.id())).hasStatus(204);
    }

    @Test
    void membersAndViewersCannotManageAnyone() {
        Caller owner = signedIn("Ana");
        String org = organizationOf(owner);
        Caller member = memberOf(owner, org, Role.MEMBER);
        Caller viewer = memberOf(owner, org, Role.VIEWER);

        for (Caller caller : List.of(member, viewer)) {
            assertThat(add(caller, org, signedIn("Luis").email(), "VIEWER")).hasStatus(403);
        }
        assertThat(patch(member, org, viewer.id(), "MEMBER", null)).hasStatus(403);
        assertThat(remove(member, org, viewer.id())).hasStatus(403);
    }

    @Test
    void nobodyRaisesTheirOwnRole() {
        Caller owner = signedIn("Ana");
        String org = organizationOf(owner);
        Caller admin = memberOf(owner, org, Role.ADMIN);
        Caller viewer = memberOf(owner, org, Role.VIEWER);

        // Acceptance: an ADMIN cannot make themselves OWNER
        assertThat(patch(admin, org, admin.id(), "OWNER", null)).hasStatus(403);
        assertThat(patch(viewer, org, viewer.id(), "MEMBER", null)).hasStatus(403);
        assertThat(getOrganization(admin, org))
                .bodyJson()
                .extractingPath("$.myRole")
                .isEqualTo("ADMIN");
        // Going down needs no permission, as leaving does not
        assertThat(patch(admin, org, admin.id(), "MEMBER", null)).hasStatus(200);
        assertThat(patch(viewer, org, viewer.id(), "VIEWER", null)).hasStatus(200);
    }

    @Test
    void demotingTheOnlyOwnerIsAConflictWhoeverAsks() {
        Caller owner = signedIn("Ana");
        String org = organizationOf(owner);
        Caller admin = memberOf(owner, org, Role.ADMIN);

        // The invariant is checked before the permission, so that the loser of two OWNERs demoting each other gets
        // 409 (see MembershipService#changeRole)
        assertThat(patch(admin, org, owner.id(), "VIEWER", null)).hasStatus(409);
        assertThat(remove(admin, org, owner.id())).hasStatus(409);
        assertThat(getOrganization(owner, org))
                .bodyJson()
                .extractingPath("$.myRole")
                .isEqualTo("OWNER");
    }

    @Test
    void theLastOwnerCannotLeaveOrStepDown() {
        Caller owner = signedIn("Ana");
        String org = organizationOf(owner);

        // Acceptance: the last OWNER cannot leave
        assertThat(remove(owner, org, owner.id()))
                .hasStatus(409)
                .bodyJson()
                .extractingPath("$.code")
                .isEqualTo("business-rule-violation");
        assertThat(patch(owner, org, owner.id(), "ADMIN", null)).hasStatus(409);

        Caller second = memberOf(owner, org, Role.OWNER);
        assertThat(patch(owner, org, owner.id(), "ADMIN", null)).hasStatus(200);
        assertThat(remove(second, org, owner.id())).hasStatus(204);
        assertThat(remove(second, org, second.id())).hasStatus(409);
    }

    @Test
    void anyMemberCanLeave() {
        Caller owner = signedIn("Ana");
        String org = organizationOf(owner);
        Caller viewer = memberOf(owner, org, Role.VIEWER);

        assertThat(remove(viewer, org, viewer.id())).hasStatus(204);

        assertThat(getOrganization(viewer, org)).hasStatus(404);
        assertThat(list(owner, org, ""))
                .bodyJson()
                .extractingPath("$.page.totalElements")
                .isEqualTo(1);
    }

    @Test
    void addingAnUnknownEmailOrSomeoneAlreadyInIsRejected() {
        Caller owner = signedIn("Ana");
        String org = organizationOf(owner);
        Caller viewer = memberOf(owner, org, Role.VIEWER);

        assertThat(add(owner, org, uniqueEmail(), "VIEWER"))
                .hasStatus(404)
                .bodyJson()
                .extractingPath("$.code")
                .isEqualTo("resource-not-found");
        assertThat(add(owner, org, viewer.email(), "MEMBER"))
                .hasStatus(409)
                .bodyJson()
                .extractingPath("$.code")
                .isEqualTo("conflict");
        assertThat(add(owner, org, "not an email", "BOSS")).hasStatus(400);
        // Whether an email is registered stays hidden from members who cannot add anyone
        assertThat(add(viewer, org, uniqueEmail(), "VIEWER")).hasStatus(403);
    }

    @Test
    void theFiftyFirstMemberIsOverTheQuota() {
        Caller owner = signedIn("Ana");
        String org = organizationOf(owner);
        for (int i = 0; i < 49; i++) {
            insertMember(org, insertUser(), Role.VIEWER);
        }

        assertThat(add(owner, org, signedIn("Luis").email(), "VIEWER"))
                .hasStatus(422)
                .bodyJson()
                .extractingPath("$.code")
                .isEqualTo("quota-exceeded");
    }

    @Test
    void someoneFromAnotherOrganizationGets404AndChangesNothing() {
        Caller owner = signedIn("Ana");
        String org = organizationOf(owner);
        Caller viewer = memberOf(owner, org, Role.VIEWER);
        Caller mallory = signedIn("Mallory");
        organizationOf(mallory);

        assertThat(list(mallory, org, "")).hasStatus(404);
        assertThat(add(mallory, org, mallory.email(), "OWNER")).hasStatus(404);
        assertThat(patch(mallory, org, viewer.id(), "OWNER", null)).hasStatus(404);
        assertThat(remove(mallory, org, viewer.id())).hasStatus(404);
        assertThat(getOrganization(viewer, org))
                .bodyJson()
                .extractingPath("$.myRole")
                .isEqualTo("VIEWER");
        // A user of another organization is not a member here, whoever asks
        assertThat(patch(owner, org, mallory.id(), "VIEWER", null)).hasStatus(404);
        assertThat(remove(owner, org, UUID.randomUUID().toString())).hasStatus(404);
    }

    @Test
    void aDeletedOrganizationHasNoMembersToManage() {
        Caller owner = signedIn("Ana");
        String org = organizationOf(owner);
        Caller viewer = memberOf(owner, org, Role.VIEWER);
        assertThat(mvc.delete()
                        .uri("/api/v1/organizations/" + org)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + owner.token())
                        .exchange())
                .hasStatus(204);

        assertThat(list(owner, org, "")).hasStatus(404);
        assertThat(patch(owner, org, viewer.id(), "MEMBER", null)).hasStatus(404);
        assertThat(remove(viewer, org, viewer.id())).hasStatus(404);
    }

    @Test
    void aStaleIfMatchIsAFailedPrecondition() {
        Caller owner = signedIn("Ana");
        String org = organizationOf(owner);
        Caller viewer = memberOf(owner, org, Role.VIEWER);

        assertThat(patch(owner, org, viewer.id(), "MEMBER", "\"0\""))
                .hasStatus(200)
                .headers()
                .hasValue(HttpHeaders.ETAG, "\"1\"");
        assertThat(patch(owner, org, viewer.id(), "VIEWER", "\"0\""))
                .hasStatus(412)
                .bodyJson()
                .extractingPath("$.code")
                .isEqualTo("precondition-failed");
        assertThat(patch(owner, org, viewer.id(), null, null))
                .hasStatus(200)
                .bodyJson()
                .extractingPath("$.role")
                .isEqualTo("MEMBER");
    }

    @Test
    void isDocumentedInOpenApiWithItsErrors() {
        MvcTestResult result = mvc.get().uri("/v3/api-docs").exchange();

        assertThat(result)
                .bodyJson()
                .extractingPath("$.paths['/api/v1/organizations/{orgId}/members'].post.responses")
                .asMap()
                .containsKeys("201", "400", "401", "403", "404", "409", "422");
        assertThat(result)
                .bodyJson()
                .extractingPath("$.paths['/api/v1/organizations/{orgId}/members/{userId}'].patch.responses")
                .asMap()
                .containsKeys("200", "400", "401", "403", "404", "409", "412");
        assertThat(result)
                .bodyJson()
                .extractingPath("$.paths['/api/v1/organizations/{orgId}/members/{userId}'].delete.responses")
                .asMap()
                .containsKeys("204", "401", "403", "404", "409");
    }

    /** A signed-in user: the access token, the id and the email. */
    private record Caller(String token, String id, String email) {}

    private Caller signedIn(String displayName) {
        String email = uniqueEmail();
        MvcTestResult registration = mvc.post()
                .uri("/api/v1/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"email": "%s", "displayName": "%s", "password": "%s"}
                        """.formatted(email, displayName, PASSWORD))
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
        return new Caller(read(login, "$.accessToken"), read(registration, "$.id"), email);
    }

    private String organizationOf(Caller owner) {
        MvcTestResult result = mvc.post()
                .uri("/api/v1/organizations")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + owner.token())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\": \"CharityLink\"}")
                .exchange();
        assertThat(result).hasStatus(201);
        return read(result, "$.id");
    }

    private Caller memberOf(Caller owner, String org, Role role) {
        Caller caller = signedIn("Member");
        assertThat(add(owner, org, caller.email(), role.name())).hasStatus(201);
        return caller;
    }

    private UUID insertUser() {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO users (id, email, display_name, password_hash, created_at, updated_at)
                VALUES (?, ?, 'Filler', '{bcrypt}not-a-real-hash', now(), now())""", id, uniqueEmail());
        return id;
    }

    private void insertMember(String org, UUID userId, Role role) {
        jdbc.update("""
                INSERT INTO memberships (organization_id, user_id, role, created_at, updated_at)
                VALUES (?, ?, ?, now(), now())""", UUID.fromString(org), userId, role.name());
    }

    private MvcTestResult add(Caller caller, String org, String email, String role) {
        return mvc.post()
                .uri("/api/v1/organizations/" + org + "/members")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + caller.token())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\": \"%s\", \"role\": \"%s\"}".formatted(email, role))
                .exchange();
    }

    private MvcTestResult list(Caller caller, String org, String query) {
        return mvc.get()
                .uri("/api/v1/organizations/" + org + "/members" + query)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + caller.token())
                .exchange();
    }

    private MvcTestResult patch(
            Caller caller, String org, String userId, @Nullable String role, @Nullable String ifMatch) {
        var request = mvc.patch()
                .uri("/api/v1/organizations/" + org + "/members/" + userId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + caller.token())
                .contentType(MediaType.APPLICATION_JSON)
                .content(role == null ? "{}" : "{\"role\": \"%s\"}".formatted(role));
        if (ifMatch != null) {
            request.header(HttpHeaders.IF_MATCH, ifMatch);
        }
        return request.exchange();
    }

    private MvcTestResult remove(Caller caller, String org, String userId) {
        return mvc.delete()
                .uri("/api/v1/organizations/" + org + "/members/" + userId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + caller.token())
                .exchange();
    }

    private MvcTestResult getOrganization(Caller caller, String org) {
        return mvc.get()
                .uri("/api/v1/organizations/" + org)
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
