package io.aeyer.plowshare.server.api;

import io.aeyer.plowshare.protocol.Home;
import io.aeyer.plowshare.server.images.ImageStore;
import io.aeyer.plowshare.server.images.StoredImage;
import io.aeyer.plowshare.server.requests.RequestedHome;
import io.aeyer.plowshare.server.requests.RequestedImageName;
import java.io.IOException;
import java.io.UncheckedIOException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * {@code POST /v1/images} — the one door an image comes in by, and the only thing in this server
 * that mints an image UID.
 *
 * <h2>An HTTP route and deliberately not a tool</h2>
 *
 * <p>The producer is a person or a job; the consumer is an agent that is <em>handed</em> a UID.
 * There is no {@code image_store} in {@code JobRuntime.knownTools()} and there is not going to be
 * one: an agent that could create an image would need bytes to create it from, which is the
 * byte-returning file tool the design rejected — one that bypasses every text-shaped assumption
 * below {@code file_read} and needs its own argument about what an agent may read as bytes. Naming
 * a UID needs none. It is the same rule that keeps {@code project_lend} out of the tool set: the
 * agent names a thing, the server decides what that means.
 *
 * <h2>Multipart, and only multipart</h2>
 *
 * <p>{@code DocumentController}'s rule and its reason, unchanged: a route taking a path for the
 * <em>server</em> to read off its own filesystem is the leash-bypass slice 2 exists to prevent. The
 * client reads the file under the workspace leash and uploads the bytes; the server never touches
 * the user's disk. There is no JSON-with-base64 alternative either — one body shape, so there is
 * one place the cap is counted and one thing a refusal is about.
 *
 * <h2>Three refusals, three statuses</h2>
 *
 * <p>{@code 400} for an empty upload, {@code 415} for bytes that are not one of the four formats a
 * vision endpoint takes, and {@code 413} for a good image over {@code plowshare.images.max-bytes}.
 * They are separated because the remedies are: send something, convert it, or shrink it. {@code
 * UnstorableImageException} carries which, so this class does not read prose to decide a number.
 *
 * <h2>Nothing above is decided here any more</h2>
 *
 * <p>This class held two refusals of its own until the breadth plan's Task 6 — a blank {@code
 * name}, and a deployment with no data directory to write to — and both moved out although <b>this
 * endpoint is ruled to get no frame at all</b>: bytes in a text frame have no precedent on this
 * server, which is a fact about transport rather than about where a rule belongs. The first is now
 * {@code requests.RequestedImageName}, beside the identical rule {@code POST /v1/documents}
 * follows; the second is {@code ImageStore.store}'s own, since whether anything can be held at all
 * is the store's fact and not a route's. So this method resolves two request fields and calls one
 * store method, and every status below is decided by something a second surface could reach.
 *
 * <p>There is no {@code GET}. Nothing in this server reads an image back except the code that
 * attaches it to a model call, and a route serving the bytes to whoever asks would be a second way
 * to reach the one directory the file fence exists to make unreachable.
 *
 * <p><b>And a route that merely <em>listed</em> them would be worse than it looks</b>, which is why
 * the absence is a test and not this paragraph. Since 2026-09-08 an agent may hand on an id it was
 * told about rather than only one it was shown, and what makes that equivalent to the old rule is
 * that an id can be given and never discovered. A {@code GET /v1/images}, or a gallery for a
 * console, is where that would stop being true. {@code NothingEnumeratesImagesTest} enumerates
 * every route this application publishes and expects exactly the one below; §6 of {@code
 * implementation rationale} is the argument to read before making it go green another way.
 */
@RestController
public class ImageController {

  private final ImageStore images;

  public ImageController(ImageStore images) {
    this.images = images;
  }

  /**
   * Store an image and answer with its UID.
   *
   * <p>{@code 201} and the record. Not {@code 202} and a job, which is {@code
   * DocumentController.upload}'s shape and would be wrong here: an ingest derives, persists and
   * embeds, which is minutes, and this writes two files bounded at {@code
   * plowshare.images.max-bytes}, five mebibytes by default. A caller handed a job id and told to
   * poll it to learn its own UID is worse off than one that has it.
   *
   * @param file the bytes. The format is read from them and never from this part's {@code
   *     Content-Type} or filename — see {@code ImageFormat}
   * @param project whose image it is; absent uses the authenticated account's Personal space. An
   *     explicit project requires Contributor access.
   * @param name what to record the image as, or absent to record the uploaded filename. Blank is
   *     refused rather than defaulted, on {@code DocumentController.upload}'s reasoning: a caller
   *     who meant to name this and sent the field empty should be told, not quietly filed
   */
  private io.aeyer.plowshare.server.personal.PersonalSpaces personal;

  @org.springframework.beans.factory.annotation.Autowired
  public void usePersonal(io.aeyer.plowshare.server.personal.PersonalSpaces personal) {
    this.personal = personal;
  }

  public ResponseEntity<StoredImageView> upload(MultipartFile file, String project, String name) {
    return upload(file, project, name, null);
  }

  @PostMapping("/v1/images")
  public ResponseEntity<StoredImageView> upload(
      @RequestParam("file") MultipartFile file,
      @RequestParam(value = "project", required = false) String project,
      @RequestParam(value = "name", required = false) String name,
      @org.springframework.web.bind.annotation.RequestAttribute(
              name = io.aeyer.plowshare.server.auth.AuthFilter.HANDLE_ATTRIBUTE,
              required = false)
          String account) {

    String declared = RequestedImageName.in(name, file.getOriginalFilename());
    Home home = personal == null ? RequestedHome.in(project) : personal.home(project, account);
    StoredImage stored = images.store(home, declared, read(file));
    return ResponseEntity.status(HttpStatus.CREATED).body(StoredImageView.of(stored));
  }

  /**
   * The whole upload in memory, which it already is.
   *
   * <p>{@code MultipartFile.getBytes} on a part Spring has already buffered, so this is a copy and
   * not a read. Bounded twice: by {@code spring.servlet.multipart.max-file-size} before this method
   * is entered at all, and by {@code plowshare.images.max-bytes} inside the store. The first is the
   * container's protection against a body it has to hold; the second is this feature's decision
   * about what it will show a model, and it is much the smaller of the two.
   */
  private static byte[] read(MultipartFile file) {
    try {
      return file.getBytes();
    } catch (IOException unreadable) {
      throw new UncheckedIOException(
          "the uploaded image could not be read from the request", unreadable);
    }
  }
}
