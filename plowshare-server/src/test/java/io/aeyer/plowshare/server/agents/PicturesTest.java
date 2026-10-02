package io.aeyer.plowshare.server.agents;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.aeyer.plowshare.protocol.Home;
import io.aeyer.plowshare.server.faults.CallerFault;
import io.aeyer.plowshare.server.faults.NotFoundFault;
import io.aeyer.plowshare.server.images.ImageStore;
import io.aeyer.plowshare.server.llm.dispatch.Content;
import io.aeyer.plowshare.server.llm.dispatch.Sampling;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Turning the image ids a run named into the bytes it is shown: the whole
 * decision, below the surface that asked for it.
 *
 * <p>{@code AgentControllerTest} still measures the same refusals through
 * HTTP, and deliberately: what that file proves is that the status and the
 * body a caller sees did not move when the decision did. This file proves the
 * decision itself — every refusal, and above all <b>the order they are reached
 * in</b>, which is the half no status can show from one request at a time.
 *
 * <p><b>{@link #a_uid_this_tier_does_not_hold_is_a_miss_and_not_a_malformed_id}
 * is the point of this class.</b> The store is asked whether it holds the id
 * before the bytes are ever asked for, so a well-formed id nothing here holds
 * is a miss naming it rather than whatever asking for absent bytes would
 * raise. The two are different facts about the caller, and only the order
 * decides which one it is told.
 *
 * <p>A real {@link ImageStore} over a {@code @TempDir} rather than a mock: what
 * this decision does is turn a UID into bytes, and a mock would assert that a
 * method was called rather than that a picture reached the run.
 */
class PicturesTest {

    /** Enough of a PNG for the store to take it: the signature and four bytes
     *  of body. */
    private static final byte[] PNG = {
        (byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 1, 2, 3, 4};

    /** An id that is shaped exactly right and names nothing anywhere. */
    private static final String ABSENT = "img_" + "0".repeat(32);

    private ImageStore images;
    private Pictures pictures;

    @BeforeEach
    void setUp(@TempDir Path tmp) {
        images = new ImageStore(
                home -> home.isGlobal() ? tmp.resolve("global") : tmp.resolve(home.project()),
                4096);
        pictures = new Pictures(images);
    }

    // --- what a run that is shown something gets ---------------------------------

    @Test
    void a_uid_becomes_the_bytes_behind_it() {
        String uid = images.store(Home.global(), "red.png", PNG).id();

        List<Content.Image> shown =
                pictures.pictures(seeing("figure_reader"), Home.global(), List.of(uid));

        assertEquals(1, shown.size());
        assertEquals(uid, shown.get(0).uid());
        assertEquals("data:image/png;base64," + Base64.getEncoder().encodeToString(PNG),
                shown.get(0).dataUri());
    }

    /** A run that named none is shown none, and nothing else here is even
     *  asked — including of an agent that cannot see. */
    @Test
    void a_run_that_named_no_pictures_is_shown_none_and_needs_no_vision() {
        assertEquals(List.of(),
                pictures.pictures(agent("scribe"), Home.global(), List.of()));
    }

    /**
     * A null list is a run that named none, and not a 500.
     *
     * <p>{@code Runs.Ask}'s compact constructor normalises null to {@code
     * List.of()}, so nothing arriving through {@code Runs.start} can reach
     * this method with one — and this is a public {@code @Service} a frame
     * handler may call directly, without a body parsed into an {@code Ask}
     * first. Before the guard, {@code uids.isEmpty()} was the first line and
     * the answer was a {@code NullPointerException}: a 500 the controller
     * could not reach and the new surface could. Absent and empty are the
     * same statement about a run, so they get the same answer rather than a
     * refusal that would make a frame carrying no {@code images} key at all
     * an error.
     */
    @Test
    void a_null_list_is_a_run_that_named_none_and_not_a_failure() {
        assertEquals(List.of(),
                pictures.pictures(agent("scribe"), Home.global(), null));
    }

    // --- the refusals ------------------------------------------------------------

    /**
     * The second half of the capability check: {@code AgentsConfig} refuses at
     * boot an agent that declares vision and names a model no pool declares as
     * seeing; this refuses at submission an agent that never declared it and is
     * being handed a picture anyway.
     */
    @Test
    void an_agent_that_never_declared_vision_is_not_shown_a_picture() {
        String uid = images.store(Home.global(), "red.png", PNG).id();

        CallerFault refused = assertThrows(CallerFault.class,
                () -> pictures.pictures(agent("scribe"), Home.global(), List.of(uid)));

        assertTrue(refused.getMessage().contains(
                "the agent 'scribe' has not declared 'vision: true'"), refused.getMessage());
        assertTrue(refused.getMessage().contains("answers that it saw nothing"),
                refused.getMessage());
    }

    /** A string that is not an id at all is the caller's own mistake, and it
     *  never reaches the filesystem. */
    @Test
    void a_string_that_is_not_an_image_id_is_refused() {
        CallerFault refused = assertThrows(CallerFault.class, () -> pictures.pictures(
                seeing("figure_reader"), Home.global(), List.of("../../etc/passwd")));

        assertTrue(refused.getMessage().contains(
                "'../../etc/passwd' is not an image id"), refused.getMessage());
        assertTrue(refused.getMessage().contains("32 hexadecimal characters"),
                refused.getMessage());
    }

    /** An image belongs to the tier it was uploaded to, so a run in one project
     *  cannot be shown another's. */
    @Test
    void a_run_in_one_project_is_not_shown_anothers_picture() {
        String uid = images.store(Home.of("payments"), "red.png", PNG).id();

        NotFoundFault missing = assertThrows(NotFoundFault.class, () -> pictures.pictures(
                seeing("figure_reader"), Home.of("ledger"), List.of(uid)));

        assertTrue(missing.getMessage().contains("the project ledger"), missing.getMessage());
        assertTrue(missing.getMessage().contains(uid), missing.getMessage());
    }

    // --- the ordering ------------------------------------------------------------

    /**
     * <b>The rule nothing refuses.</b> The store is asked whether it holds the
     * id before the bytes are asked for, so an id this tier does not hold is a
     * miss naming it — {@link NotFoundFault}, which both surfaces answer 404 —
     * and not the malformed-argument complaint that asking for absent bytes
     * raises.
     *
     * <p>Ask for the bytes first and the same request answers with a different
     * fault entirely, for input that is wrong in exactly one way.
     */
    @Test
    void a_uid_this_tier_does_not_hold_is_a_miss_and_not_a_malformed_id() {
        NotFoundFault missing = assertThrows(NotFoundFault.class, () -> pictures.pictures(
                seeing("figure_reader"), Home.global(), List.of(ABSENT)));

        assertTrue(missing.getMessage().contains("this server has no image " + ABSENT),
                missing.getMessage());
        assertTrue(missing.getMessage().contains("the global tier"), missing.getMessage());
    }

    /**
     * The vision check before the ids are read at all, sent as one call that is
     * wrong in both ways.
     *
     * <p>Swap the two and an agent that could never have been shown anything is
     * told to correct an id instead.
     */
    @Test
    void an_agent_that_cannot_see_is_refused_before_its_ids_are_read() {
        CallerFault refused = assertThrows(CallerFault.class, () -> pictures.pictures(
                agent("scribe"), Home.global(), List.of("not-an-id")));

        assertTrue(refused.getMessage().contains("has not declared 'vision: true'"),
                refused.getMessage());
    }

    /**
     * And the shape of an id before the store is asked for it: a string that is
     * not an id is refused where it stands, rather than reported as something
     * this tier does not hold.
     */
    @Test
    void a_malformed_id_is_refused_before_the_store_is_asked_for_it() {
        CallerFault refused = assertThrows(CallerFault.class, () -> pictures.pictures(
                seeing("figure_reader"), Home.global(), List.of("img_nope")));

        assertTrue(refused.getMessage().contains("is not an image id"), refused.getMessage());
    }

    /** The ids are resolved in the order they were named, so a run sees its
     *  pictures the way it asked for them. */
    @Test
    void the_pictures_come_back_in_the_order_they_were_named() {
        String first = images.store(Home.global(), "red.png", PNG).id();
        String second = images.store(Home.global(), "blue.png",
                new byte[] {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 9, 9, 9, 9})
                .id();

        List<Content.Image> shown = pictures.pictures(
                seeing("figure_reader"), Home.global(), List.of(second, first));

        assertEquals(List.of(second, first), shown.stream().map(Content.Image::uid).toList());
    }

    // --- fixtures ----------------------------------------------------------------

    /** An agent that has not declared that it can see. */
    private static AgentDefinition agent(String name) {
        return new AgentDefinition(
                name, "a fixture", "fast", List.of(), List.of(), List.of(), 2, 4,
                "You do one thing.", true, true);
    }

    /** The same fixture with the one declaration this decision is about.
     *  {@code AgentControllerTest.seeing}'s own shape: {@code vision} is on the
     *  canonical constructor only, so naming it means naming sampling too. */
    private static AgentDefinition seeing(String name) {
        return new AgentDefinition(
                name, "a fixture", "fast", Sampling.Intent.DEFAULT, Sampling.NONE,
                List.of(), List.of(), List.of(), 2, 4, "You look at things.", true, true, true);
    }
}
