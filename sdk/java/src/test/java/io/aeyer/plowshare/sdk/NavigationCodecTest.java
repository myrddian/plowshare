package io.aeyer.plowshare.sdk;

import static org.junit.jupiter.api.Assertions.*;

import io.aeyer.plowshare.protocol.*;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class NavigationCodecTest {
  private static final String ID = "11111111-1111-1111-1111-111111111111";

  @Test
  void savedPathRoundTripsWithoutSupplyingOptionalNulls() throws Exception {
    String input =
        "{\"scope\":{\"kind\":\"personal\"},\"requestId\":\""
            + ID
            + "\",\"id\":\""
            + ID
            + "\",\"expectedVersion\":0,\"label\":\"Billing\",\"path\":{\"steps\":[{\"kind\":\"scope\",\"scope\":{\"kind\":\"personal\"}},{\"kind\":\"document\",\"target\":{\"resource\":\""
            + ID
            + "\"}}]}}";
    var command = InformationCodec.decode("views.save", input);
    String encoded = InformationCodec.encode(command);
    assertFalse(encoded.contains(":null"));
    assertEquals(command, InformationCodec.decode("views.save", encoded));
  }

  @Test
  void lockedAndUnavailableVariantsDoNotExposePartialRevisionObjects() throws Exception {
    var command =
        new InformationRequest.DocumentGet(
            InformationRequest.Selection.personal(),
            new InformationNavigation.Target(UUID.fromString(ID), null));
    var response =
        assertInstanceOf(
            InformationResponse.NavigationSelection.class,
            InformationCodec.response(
                command,
                "{\"kind\":\"locked\",\"resource\":\""
                    + ID
                    + "\",\"label\":\"Approved label\",\"future\":true}"));
    assertInstanceOf(InformationNavigation.Locked.class, response.value());
    assertInstanceOf(
        InformationNavigation.Unavailable.class,
        assertInstanceOf(
                InformationResponse.NavigationSelection.class,
                InformationCodec.response(command, "{\"kind\":\"unavailable\"}"))
            .value());
    assertThrows(
        java.io.IOException.class,
        () -> InformationCodec.response(command, "{\"kind\":\"readable\"}"));
  }

  @Test
  void malformedCommandsFailBeforeSubmission() {
    for (String input :
        new String[] {
          "{\"scope\":{\"kind\":\"personal\"},\"target\":{\"resource\":\"short\"}}",
          "{\"scope\":{\"kind\":\"personal\"},\"target\":{\"resource\":\""
              + ID
              + "\",\"revision\":null}}",
          "{\"scope\":{\"kind\":\"personal\"},\"target\":{\"resource\":\""
              + ID
              + "\",\"grant\":true}}"
        })
      assertThrows(
          java.io.IOException.class, () -> InformationCodec.decode("documents.get", input));
    assertThrows(
        java.io.IOException.class,
        () ->
            InformationCodec.decode(
                "documents.list", "{\"scope\":{\"kind\":\"personal\"},\"limit\":1.5}"));
  }
}
