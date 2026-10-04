package io.aeyer.plowshare.server.images;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.aeyer.plowshare.protocol.Home;
import io.aeyer.plowshare.protocol.ImageFormat;
import io.aeyer.plowshare.server.faults.CallerFault;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * What the store holds, what it refuses, and the one property everything later rests on: nothing is
 * written without a record.
 *
 * <p>Every fixture is under a {@link TempDir} and no test here touches a real data directory —
 * {@code DataLayoutTest}'s rule, and the reason is a test in this repository that was deleting a
 * real {@code config/application.yml} until it was found.
 *
 * <p>The images are signatures and a few bytes. That is deliberate: {@link ImageFormat} reads a
 * prefix and is documented as not being a decoder, so a fixture carrying a real encoded picture
 * would be asserting something no code here looks at, and would make every test in this file harder
 * to read for it.
 */
class ImageStoreTest {

  private static final byte[] PNG = {
    (byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 1, 2, 3, 4
  };
  private static final byte[] JPEG = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 9, 9};
  private static final byte[] GIF = {'G', 'I', 'F', '8', '9', 'a', 7};
  private static final byte[] WEBP = {'R', 'I', 'F', 'F', 4, 0, 0, 0, 'W', 'E', 'B', 'P', 1};

  private static ImageStore storeIn(Path root, int maxBytes) {
    // The tier decides the directory, exactly as ImageDirectories.under does
    // through ProjectIds -- with the name standing in for the id, because
    // what this file tests is the layout and not the translation. The bound
    // binding is tested where it can be: against a database.
    return new ImageStore(
        home -> home.isGlobal() ? root.resolve("global") : root.resolve(home.project()), maxBytes);
  }

  @Test
  void an_image_is_stored_under_a_uid_derived_from_its_bytes(@TempDir Path tmp) {
    ImageStore store = storeIn(tmp, 4096);

    StoredImage stored = store.store(Home.of("atlas"), "red.png", PNG);

    assertEquals(
        ImageStore.idFor(PNG),
        stored.id(),
        "the id is the bytes and nothing else, which is what makes a re-upload"
            + " idempotent rather than a second image");
    assertTrue(stored.id().startsWith("img_"));
    assertEquals(32, stored.id().length() - "img_".length());
    assertEquals(ImageFormat.PNG, stored.format());
    assertEquals("red.png", stored.filename());
    assertEquals(PNG.length, stored.bytes());
    assertEquals("atlas", stored.home().project());
  }

  /**
   * The property promoted from PayloadExport: a record exists for everything in the tree, so
   * retention and orphan cleanup have something to reason over and "what is in here" is answerable.
   */
  @Test
  void nothing_is_written_without_a_record_beside_it(@TempDir Path tmp) throws IOException {
    StoredImage stored = storeIn(tmp, 4096).store(Home.of("atlas"), "red.png", PNG);

    Path directory = tmp.resolve("atlas");
    assertTrue(Files.isRegularFile(directory.resolve(stored.fileName())));
    assertTrue(Files.isRegularFile(directory.resolve(stored.recordName())));

    String record =
        Files.readString(directory.resolve(stored.recordName()), StandardCharsets.UTF_8);
    assertTrue(
        record.contains("\"project\":\"atlas\""),
        "the project's NAME on the record even though the directory is its id:"
            + " a person reading the file wants the word they typed");
    assertTrue(record.contains("\"format\":\"png\""));
    assertTrue(record.contains("\"filename\":\"red.png\""));
    assertTrue(record.contains("\"bytes\":" + PNG.length));
  }

  /**
   * V1's rule, in a file: the global tier is the absence of a project and never a project named
   * 'global'.
   */
  @Test
  void a_global_image_records_a_null_project_and_not_the_word(@TempDir Path tmp)
      throws IOException {
    StoredImage stored = storeIn(tmp, 4096).store(Home.global(), "chart.gif", GIF);

    assertNull(stored.home().project());
    String record =
        Files.readString(
            tmp.resolve("global").resolve(stored.recordName()), StandardCharsets.UTF_8);
    assertTrue(
        record.contains("\"project\":null"),
        "spelled as a name, a real project called 'global' could not be told"
            + " apart from the tier every agent reads");
  }

  @Test
  void the_record_comes_back_with_the_bytes_and_the_name_it_was_stored_under(@TempDir Path tmp) {
    ImageStore store = storeIn(tmp, 4096);
    StoredImage stored = store.store(Home.of("atlas"), "red.png", PNG);

    Optional<StoredImage> found = store.find(Home.of("atlas"), stored.id());

    assertTrue(found.isPresent());
    assertEquals(
        stored,
        found.orElseThrow(),
        "read back through JSON, which is the round trip an operator's directory"
            + " has to survive");
  }

  /**
   * Content addressing is only worth having if the second upload is not a second image, and only
   * safe if it does not change what a UID already handed out resolves to.
   */
  @Test
  void the_same_bytes_uploaded_twice_are_one_image_and_the_first_write_wins(@TempDir Path tmp)
      throws IOException {
    ImageStore store = storeIn(tmp, 4096);
    StoredImage first = store.store(Home.of("atlas"), "red.png", PNG);

    StoredImage again = store.store(Home.of("atlas"), "a-different-name.png", PNG);

    assertEquals(first, again);
    assertEquals(
        "red.png",
        again.filename(),
        "the name recorded is the one it was first stored under; a UID an agent"
            + " already holds must not start describing something else");
    try (var listed = Files.list(tmp.resolve("atlas"))) {
      assertEquals(2, listed.count(), "one image is one record and one file");
    }
  }

  @Test
  void two_tiers_hold_the_same_bytes_separately(@TempDir Path tmp) {
    ImageStore store = storeIn(tmp, 4096);
    StoredImage mine = store.store(Home.of("atlas"), "red.png", PNG);
    store.store(Home.global(), "red.png", PNG);

    assertTrue(store.find(Home.global(), mine.id()).isPresent());
    assertTrue(store.find(Home.of("atlas"), mine.id()).isPresent());
    assertTrue(
        store.find(Home.of("ledger"), mine.id()).isEmpty(),
        "a project that never held it does not, which is what makes a lookup a"
            + " question about one tier rather than about the disk");
  }

  @Test
  void the_bytes_reach_a_caller_only_as_a_data_uri(@TempDir Path tmp) {
    ImageStore store = storeIn(tmp, 4096);
    StoredImage stored = store.store(Home.of("atlas"), "red.png", PNG);

    String uri = store.dataUri(Home.of("atlas"), stored.id());

    assertEquals("data:image/png;base64," + Base64.getEncoder().encodeToString(PNG), uri);
    assertTrue(
        uri.startsWith("data:"),
        "there is no other shape this method can answer with, which is what makes"
            + " a server-side fetch of a caller-supplied URL unspellable");
  }

  @Test
  void every_format_a_vision_endpoint_takes_declares_its_own_media_type(@TempDir Path tmp) {
    ImageStore store = storeIn(tmp, 4096);
    Home home = Home.of("atlas");

    assertTrue(
        store
            .dataUri(home, store.store(home, "a.png", PNG).id())
            .startsWith("data:image/png;base64,"));
    assertTrue(
        store
            .dataUri(home, store.store(home, "b.jpg", JPEG).id())
            .startsWith("data:image/jpeg;base64,"));
    assertTrue(
        store
            .dataUri(home, store.store(home, "c.gif", GIF).id())
            .startsWith("data:image/gif;base64,"));
    assertTrue(
        store
            .dataUri(home, store.store(home, "d.webp", WEBP).id())
            .startsWith("data:image/webp;base64,"));
  }

  /**
   * The format is read from the bytes, so a caller who mislabels one gets the media type the
   * endpoint can actually decode.
   */
  @Test
  void the_declared_name_does_not_decide_the_format(@TempDir Path tmp) {
    ImageStore store = storeIn(tmp, 4096);

    StoredImage stored = store.store(Home.of("atlas"), "definitely-a.png", JPEG);

    assertEquals(ImageFormat.JPEG, stored.format());
    assertTrue(
        store.dataUri(Home.of("atlas"), stored.id()).startsWith("data:image/jpeg"),
        "a data URI whose declared type and payload disagreed would be refused"
            + " by the endpoint, about a request nothing here built wrong");
  }

  @Test
  void an_image_over_the_cap_is_refused_and_never_resampled(@TempDir Path tmp) throws IOException {
    ImageStore store = storeIn(tmp, PNG.length - 1);

    UnstorableImageException refused =
        assertThrows(
            UnstorableImageException.class, () -> store.store(Home.of("atlas"), "red.png", PNG));

    assertEquals(UnstorableImageException.Reason.TOO_LARGE, refused.reason());
    assertTrue(
        refused.getMessage().contains("plowshare.images.max-bytes"),
        "naming the key an operator would raise: " + refused.getMessage());
    assertFalse(
        Files.exists(tmp.resolve("atlas")),
        "and nothing is created, so a refused upload leaves no directory to explain");
  }

  /**
   * The cap counts the file and not its base64 encoding, which is the decision the design left
   * open. The two differ by 4/3, so a payload between the two numbers is what tells them apart.
   */
  @Test
  void the_cap_counts_raw_bytes_and_not_the_base64_payload(@TempDir Path tmp) {
    byte[] png = new byte[300];
    System.arraycopy(PNG, 0, png, 0, 8);
    int encoded = Base64.getEncoder().encodeToString(png).length();
    assertTrue(encoded > 300, "the fixture is only meaningful if the two numbers differ");

    StoredImage stored = storeIn(tmp, 320).store(Home.of("atlas"), "red.png", png);

    assertEquals(
        300,
        stored.bytes(),
        "300 raw bytes is under a 320-byte cap; its 400 bytes of base64 is not,"
            + " and a store that measured the encoding would have refused this");
  }

  @Test
  void bytes_that_are_not_an_image_this_server_can_show_are_refused(@TempDir Path tmp) {
    ImageStore store = storeIn(tmp, 4096);

    UnstorableImageException refused =
        assertThrows(
            UnstorableImageException.class,
            () ->
                store.store(
                    Home.of("atlas"), "notes.txt", "hello".getBytes(StandardCharsets.UTF_8)));

    assertEquals(UnstorableImageException.Reason.UNRECOGNISED, refused.reason());
    assertTrue(
        refused.getMessage().contains("image/png"),
        "a refusal lists the set rather than the one thing that was wrong");
  }

  /**
   * SVG is an image everywhere else and is not one here: no vision endpoint rasterises it, and it
   * is a document with a script element in its grammar.
   */
  @Test
  void an_svg_is_not_an_image_for_this_purpose(@TempDir Path tmp) {
    ImageStore store = storeIn(tmp, 4096);

    assertEquals(
        UnstorableImageException.Reason.UNRECOGNISED,
        assertThrows(
                UnstorableImageException.class,
                () ->
                    store.store(
                        Home.of("atlas"),
                        "d.svg",
                        "<svg xmlns='http://www.w3.org/2000/svg'/>"
                            .getBytes(StandardCharsets.UTF_8)))
            .reason());
  }

  @Test
  void an_empty_upload_is_not_an_image(@TempDir Path tmp) {
    ImageStore store = storeIn(tmp, 4096);

    assertEquals(
        UnstorableImageException.Reason.EMPTY,
        assertThrows(
                UnstorableImageException.class,
                () -> store.store(Home.of("atlas"), "nothing.png", new byte[0]))
            .reason());
  }

  /**
   * The containment control, and it is the reason ids are checked before they are resolved rather
   * than after.
   *
   * <p>A UID arrives from outside and becomes a file name. Without the shape check, so does a path.
   */
  @Test
  void an_id_that_is_not_an_id_never_reaches_the_filesystem(@TempDir Path tmp) throws IOException {
    ImageStore store = storeIn(tmp, 4096);
    Files.createDirectories(tmp.resolve("atlas"));
    Files.writeString(tmp.resolve("secret.json"), "{}");

    assertThrows(IllegalArgumentException.class, () -> store.find(Home.of("atlas"), "../secret"));
    assertThrows(
        IllegalArgumentException.class,
        () -> store.find(Home.of("atlas"), "img_" + "z".repeat(32)));
    assertThrows(
        IllegalArgumentException.class, () -> store.find(Home.of("atlas"), "mem_0123456789abcdef"));
    assertFalse(ImageStore.isUid("img_0123"));
    assertTrue(ImageStore.isUid("img_" + "0".repeat(32)));
  }

  @Test
  void a_uid_nothing_stored_is_a_miss_and_not_a_failure(@TempDir Path tmp) {
    ImageStore store = storeIn(tmp, 4096);

    assertEquals(Optional.empty(), store.find(Home.of("atlas"), "img_" + "0".repeat(32)));
    assertThrows(
        IllegalArgumentException.class,
        () -> store.dataUri(Home.of("atlas"), "img_" + "0".repeat(32)),
        "a caller asking for the bytes has already decided they exist, so a miss"
            + " there is a refusal rather than an empty answer");
  }

  /**
   * A record whose bytes somebody removed is the one half of the property a file tree cannot
   * enforce, and it is loud rather than silent: a model shown nothing and told it was shown a
   * picture is the worse outcome.
   */
  @Test
  void a_record_with_no_bytes_is_not_an_image(@TempDir Path tmp) throws IOException {
    ImageStore store = storeIn(tmp, 4096);
    StoredImage stored = store.store(Home.of("atlas"), "red.png", PNG);
    Files.delete(tmp.resolve("atlas").resolve(stored.fileName()));

    assertTrue(store.find(Home.of("atlas"), stored.id()).isEmpty());
    assertThrows(
        IllegalArgumentException.class, () -> store.dataUri(Home.of("atlas"), stored.id()));
  }

  @Test
  void a_deployment_that_keeps_nothing_holds_no_images() {
    assertFalse(ImageStore.NONE.keepsAnything());
    assertEquals(
        Optional.empty(),
        ImageStore.NONE.find(Home.global(), "img_" + "0".repeat(32)),
        "asked the same question as a real one and answering nowhere, which is"
            + " what stops a null check somebody eventually forgets");
    // A CallerFault and not an IllegalStateException: the breadth plan
    // moved POST /v1/images' "this server keeps no data directory" refusal
    // out of ImageController and into #store, so that the sentence naming
    // PLOWSHARE_DATA_DIR is the store's own and reaches a caller as a 400
    // through faults.Faults rather than as an unclassified 500.
    assertThrows(CallerFault.class, () -> ImageStore.NONE.store(Home.global(), "red.png", PNG));
  }

  @Test
  void two_different_images_are_two_different_ids() {
    assertNotEquals(ImageStore.idFor(PNG), ImageStore.idFor(JPEG));
    assertEquals(ImageStore.idFor(PNG), ImageStore.idFor(PNG.clone()));
  }
}
