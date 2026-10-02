package io.aeyer.plowshare.protocol;

import java.security.SecureRandom;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.random.RandomGenerator;

/**
 * Memory id generation, ported from Excalibur's {@code archive/ids.py}.
 *
 * <p>{@code mem_} + 10 hex chars of millisecond timestamp + 6 hex chars of
 * randomness. <b>Lexicographic order matches creation order</b>, which is not
 * cosmetic: the index, the store's {@code ORDER BY id} and every supersession
 * chain read in the order things happened, with no timestamp column needing to
 * be consulted or kept consistent. Ids are never reused.
 *
 * <p>The timestamp is measured from a fixed 2020 epoch rather than from 1970,
 * because millis since 1970 stopped fitting in 10 hex digits in 2004. Ten hex
 * digits from this epoch is good until 2054-11-03; a longer id would have been
 * the alternative, and the id appears in every index line an agent reads.
 */
public final class MemoryIds {

    public static final String PREFIX = "mem_";

    /** See the class note: 10 hex digits of millis from here lasts to 2054. */
    private static final Instant EPOCH = Instant.parse("2020-01-01T00:00:00Z");

    /** Three bytes, exactly Python's {@code secrets.token_hex(3)}. */
    private static final int RANDOM_BYTES = 3;

    private static final SecureRandom RANDOM = new SecureRandom();

    private static final HexFormat HEX = HexFormat.of().withUpperCase();

    private MemoryIds() {}

    /**
     * A fresh id for a memory formed at {@code at}.
     *
     * <p>The instant is passed in rather than read from the clock here, so that
     * an archive with an injected clock mints ids on the same timeline as the
     * provenance it writes — an id whose embedded timestamp disagreed with the
     * record's {@code formed_at} would sort into a position its own history
     * contradicts.
     */
    public static String newId(Instant at) {
        return newId(at, RANDOM);
    }

    /**
     * The same id shape under another prefix, for the archive's other rows.
     *
     * <p>Here rather than copied into whichever class needs it, because the
     * <em>ordering</em> property is the reusable part and it is not obvious from
     * outside: the ten hex digits are millis from a 2020 epoch, zero-padded
     * precisely so that string order is minting order, and they run out in 2054.
     * A second copy of that arithmetic elsewhere is a second thing to find when
     * that date matters, and a second chance to drop the padding — which fails
     * silently, as a list that is merely almost sorted.
     *
     * <p>The class stays named for memories because that is what the scheme was
     * ported for; {@code prp_} proposal ids are the second caller.
     *
     * @param prefix what the id starts with; {@link #PREFIX} for a memory
     * @param at the instant the row is formed, on the same clock as whatever is
     *     recorded beside it
     */
    public static String mint(String prefix, Instant at) {
        return prefix + suffix(at, RANDOM);
    }

    /**
     * The same minting against a caller-supplied source of randomness.
     *
     * <p>Package-private and for tests alone. It exists so the uniqueness test
     * can be deterministic: 500 draws over three random bytes collide about
     * 0.7% of the time by the birthday bound, and a suite that fails one run in
     * 135 for no reason teaches people to re-run rather than read. A seeded
     * generator makes that claim decidable once instead of re-rolled forever.
     *
     * <p>Production has exactly one source and it is {@link SecureRandom}: the
     * public entry point above takes no generator, so there is no way for a
     * caller to weaken it by passing one.
     */
    static String newId(Instant at, RandomGenerator random) {
        return PREFIX + suffix(at, random);
    }

    /** Everything after the prefix: the sortable timestamp and the randomness.
     *  Shared by {@link #newId(Instant)} and {@link #mint(String, Instant)} so
     *  that the padding rule below has exactly one home. */
    private static String suffix(Instant at, RandomGenerator random) {
        long millis = EPOCH.until(at, ChronoUnit.MILLIS);
        // Uppercase, zero-padded to ten: the padding is what makes string
        // ordering match time ordering. A shorter unpadded value would sort
        // before every longer one regardless of when it was minted.
        return String.format("%010X", millis) + HEX.formatHex(randomBytes(random));
    }

    private static byte[] randomBytes(RandomGenerator random) {
        byte[] bytes = new byte[RANDOM_BYTES];
        random.nextBytes(bytes);
        return bytes;
    }
}
