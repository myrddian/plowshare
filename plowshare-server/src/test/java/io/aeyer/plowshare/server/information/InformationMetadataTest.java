package io.aeyer.plowshare.server.information;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import org.junit.jupiter.api.Test;

class InformationMetadataTest {
  final String retained =
      "Written by Ada Lovelace. Published by Analytical Society. PostgreSQL notes.";

  Map<String, Object> candidate(String name, String evidence, boolean certain) {
    return Map.of("name", name, "evidence", evidence, "certain", certain);
  }

  Map<String, Object> metadata(Object person, Object organisation) {
    var result = new HashMap<String, Object>();
    result.put("autoTag", List.of("PostgreSQL"));
    result.put("documentAuthor", person);
    result.put("documentOrganisation", organisation);
    return result;
  }

  @Test
  void prefers_a_clear_person_to_a_clear_issuing_organisation() {
    var result =
        InformationMetadata.from(
            metadata(
                candidate("Ada Lovelace", "Written by Ada Lovelace.", true),
                candidate("Analytical Society", "Published by Analytical Society.", true)),
            retained);
    assertEquals("Ada Lovelace", result.author());
    assertEquals("person", result.authorSource());
    assertEquals("Written by Ada Lovelace.", result.authorEvidence());
    assertEquals(List.of("postgresql"), result.tags());
  }

  @Test
  void questionable_or_unsupported_person_uses_a_clear_organisation() {
    for (Object person :
        List.of(
            candidate("Ada Lovelace", "Written by Ada Lovelace.", false),
            candidate("Imagined Person", "Written by Imagined Person.", true),
            candidate("Unquoted Name", "Written by Ada Lovelace.", true))) {
      var result =
          InformationMetadata.from(
              metadata(
                  person,
                  candidate("Analytical Society", "Published by Analytical Society.", true)),
              retained);
      assertEquals("Analytical Society", result.author());
      assertEquals("organisation", result.authorSource());
    }
  }

  @Test
  void absent_or_questionable_organisation_leaves_attribution_for_the_account_fallback() {
    for (Object organisation :
        List.of(
            candidate("Analytical Society", "Published by Analytical Society.", false),
            candidate("Invented Organisation", "Published by Invented Organisation.", true))) {
      var result = InformationMetadata.from(metadata(null, organisation), retained);
      assertNull(result.author());
      assertNull(result.authorSource());
      assertNull(result.authorEvidence());
    }
    assertNull(InformationMetadata.from(metadata(null, null), retained).author());
  }

  @Test
  void old_paid_tag_checkpoints_do_not_invent_an_author() {
    var result = InformationMetadata.from(List.of("PostgreSQL"), retained);
    assertEquals(List.of("postgresql"), result.tags());
    assertNull(result.author());
  }

  @Test
  void categories_allow_multiple_memberships_but_cannot_create_tags() {
    assertEquals(
        Map.of("databases", List.of("postgresql"), "software", List.of("postgresql")),
        InformationTagGroups.from(
            Map.of("Databases", List.of(" PostgreSQL "), "software", List.of("postgresql")),
            List.of("postgresql")));
    assertThrows(
        io.aeyer.plowshare.server.faults.CallerFault.class,
        () ->
            InformationTagGroups.from(
                Map.of("databases", List.of("invented")), List.of("postgresql")));
    assertThrows(
        io.aeyer.plowshare.server.faults.CallerFault.class,
        () -> InformationTagGroups.from(Map.of("databases", List.of()), List.of("postgresql")));
    var raw = metadata(null, null);
    raw.put("tagGroups", Map.of("databases", List.of("postgresql")));
    assertEquals(
        Map.of("databases", List.of("postgresql")),
        InformationMetadata.from(raw, retained).groups());
    raw.put("tagGroups", Map.of("databases", List.of("sqlite")));
    assertThrows(
        io.aeyer.plowshare.server.faults.CallerFault.class,
        () -> InformationMetadata.from(raw, retained));
  }

  @Test
  void
      malformed_candidates_and_duplicate_fields_are_refused_even_when_the_other_candidate_is_valid() {
    for (String wire :
        List.of(
            "{\"autoTag\":[],\"documentAuthor\":{}}",
            "{\"autoTag\":[],\"documentAuthor\":{\"name\":\"Ada\",\"evidence\":\"Written by Ada\",\"certain\":\"true\"}}",
            "{\"autoTag\":[],\"documentAuthor\":{\"name\":7,\"evidence\":\"Written by Ada\",\"certain\":true}}",
            "{\"autoTag\":[],\"documentAuthor\":null,\"documentOrganisation\":{},\"unexpected\":true}",
            "{\"autoTag\":[],\"autoTag\":[\"sql\"]}",
            "{\"autoTag\":[]} {}")) {
      assertThrows(
          IllegalStateException.class, () -> InformationMetadataCodec.read(wire, retained), wire);
    }
  }

  @Test
  void direct_metadata_values_are_immutable_and_cannot_bypass_attribution_checks() {
    assertThrows(
        IllegalArgumentException.class,
        () -> new InformationMetadata(List.of(), null, "person", "quote"));
    assertThrows(
        IllegalArgumentException.class,
        () -> new InformationMetadata(List.of(), "Ada\n", "person", "Written by Ada\n"));
    assertThrows(
        IllegalArgumentException.class,
        () -> new InformationMetadata(List.of(), "Ada", "invented", "Written by Ada"));
    assertThrows(
        IllegalArgumentException.class,
        () -> new InformationMetadata(List.of(), "Ada", "person", "Unrelated quote"));
    var tags = new ArrayList<>(List.of("postgresql"));
    var groups = new HashMap<String, List<String>>(Map.of("databases", tags));
    var result = new InformationMetadata(tags, null, null, null, groups);
    tags.clear();
    groups.clear();
    assertEquals(Map.of("databases", List.of("postgresql")), result.groups());
    assertThrows(UnsupportedOperationException.class, () -> result.groups().clear());
  }
}
