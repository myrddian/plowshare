package io.aeyer.plowshare.server.information;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

/**
 * The home-scope-to-audience mapping, for the combinations a database fixture cannot produce.
 *
 * <p>This exists because of a pin that could not disagree. The database pin for a GLOBAL_KNOWLEDGE
 * home asserts that publication is refused, and it passes — but it also passed against a version
 * that had dropped the {@code builtin} check entirely, because the built-in knowledge account has
 * no personal project today and so the mapping returned nothing anyway. The pin was agreeing with a
 * coincidence of the current data, not with the rule.
 *
 * <p>The rule is that a built-in scope has no audience <b>whatever project it is associated
 * with</b>, and that only PROJECT and PERSONAL may reach an audience through {@code project_id}.
 * Asserting it needs rows the schema refuses to create — {@code authority_project_lifecycle}
 * forbids a personal project for a non-USER account — so the decision is asserted where it is
 * decidable instead of left to a fixture that cannot reach the branch.
 */
class InformationEventAudienceMappingTest {
  private static final long PROJECT = 7;
  private static final long PERSONAL = 9;

  @Test
  void a_project_or_personal_home_maps_to_its_own_project() {
    assertEquals(
        PROJECT, JdbcInformationEventRecords.audienceProject("PROJECT", null, PROJECT, null));
    // A personal space is a project; it needs no alias and must not be routed through one.
    assertEquals(
        PROJECT, JdbcInformationEventRecords.audienceProject("PERSONAL", null, PROJECT, PERSONAL));
  }

  @Test
  void account_knowledge_maps_to_the_owners_personal_project_and_never_to_a_scope_project() {
    assertEquals(
        PERSONAL,
        JdbcInformationEventRecords.audienceProject("ACCOUNT_KNOWLEDGE", null, null, PERSONAL));
    // The V185 alias is the personal project, not whatever project happens to be on the scope row.
    assertEquals(
        PERSONAL,
        JdbcInformationEventRecords.audienceProject("ACCOUNT_KNOWLEDGE", null, PROJECT, PERSONAL));
    assertNull(
        JdbcInformationEventRecords.audienceProject("ACCOUNT_KNOWLEDGE", null, PROJECT, null),
        "an account with no personal project has no audience, and its scope's project is not one");
  }

  @Test
  void a_builtin_scope_has_no_audience_however_many_projects_are_available_to_route_it_to() {
    // The combination the database cannot currently hold, and the one that matters: a built-in
    // knowledge scope with both a scope project and a personal project present. Decision 0039
    // defers global-read audiences, so the only correct answer is none.
    for (Long project : new Long[] {null, PROJECT})
      for (Long personal : new Long[] {null, PERSONAL})
        assertNull(
            JdbcInformationEventRecords.audienceProject(
                "ACCOUNT_KNOWLEDGE", "GLOBAL_KNOWLEDGE", project, personal),
            "GLOBAL_KNOWLEDGE must be refused before any project is considered");
    assertNull(
        JdbcInformationEventRecords.audienceProject(
            "ADMINISTRATION", "SYSTEM_ADMINISTRATION", PROJECT, PERSONAL));
  }

  @Test
  void a_restricted_administration_or_unrecognised_home_is_refused_even_holding_a_project() {
    // RESTRICTED carries a project_id in the schema, so this branch is reachable with a real
    // project in hand; refusing it is a decision, not an absence of data.
    assertNull(JdbcInformationEventRecords.audienceProject("RESTRICTED", null, PROJECT, null));
    assertNull(
        JdbcInformationEventRecords.audienceProject("ADMINISTRATION", null, PROJECT, PERSONAL));
    assertNull(
        JdbcInformationEventRecords.audienceProject("SOMETHING_NEW", null, PROJECT, PERSONAL));
    assertNull(JdbcInformationEventRecords.audienceProject(null, null, PROJECT, PERSONAL));
  }
}
