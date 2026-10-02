package io.aeyer.plowshare.server.archive;

import io.aeyer.plowshare.server.llm.EmbeddingClient;
import io.aeyer.plowshare.server.llm.EmbeddingException;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * A stand-in for the embedding endpoint, so recall's <em>rules</em> are the only
 * variable under test.
 *
 * <p>No test in this project may reach a live model. Excalibur's golden-set eval
 * was measured at anywhere from 2/4 to 4/4 on identical code: a suite built on
 * one cannot tell you whether a change helped, and a red run means "the model
 * had a bad day" as often as it means "the code is wrong". A stub makes every
 * assertion here about the query, the state filter and the tier order.
 *
 * <p>Two knobs, and both exist to reach a state the real client cannot be asked
 * for on demand:
 *
 * <ul>
 *   <li>{@link #assign} maps a substring of the embedded text to a direction, so
 *       a test can say "this question is near that memory" without a model
 *       agreeing. Everything unmatched gets {@link #ONE}, which makes every
 *       distance equal — ordering then cannot rescue a rule that fails.
 *   <li>{@link #failNext} makes exactly the next call throw, which is how the
 *       write path's behaviour under a dead endpoint gets tested without killing
 *       an endpoint.
 * </ul>
 */
final class StubEmbeddingClient implements EmbeddingClient {

    /** Matches the schema's {@code vector(768)}. A vector of any other width is
     *  rejected by Postgres, not by the stub, and the error names the column
     *  rather than the test. */
    static final int DIM = 768;

    /** Every component set, so every pair of these is at cosine distance zero. */
    static final float[] ONE = filled(1.0f);

    private final List<Function<String, float[]>> rules = new ArrayList<>();

    private boolean failNext;

    private int calls;

    /** Text containing {@code marker} embeds to the basis vector along {@code
     *  axis}. Two different axes are at cosine distance 1; the same axis is at 0. */
    void assign(String marker, int axis) {
        float[] vector = new float[DIM];
        vector[axis] = 1.0f;
        rules.add(text -> text.contains(marker) ? vector : null);
    }

    /** The next {@link #embed} or {@link #embedAll} throws, once. */
    void failNext() {
        this.failNext = true;
    }

    /** How many times the endpoint has been asked, counting the calls that
     *  threw. Zero is an assertion a test can make: the write path may now be
     *  handed a vector somebody else already paid for, and "no model call at
     *  all" is what says it took it. */
    int calls() {
        return calls;
    }

    @Override
    public float[] embed(String text) {
        return embedAll(List.of(text)).get(0);
    }

    @Override
    public List<float[]> embedAll(List<String> texts) {
        calls++;
        if (failNext) {
            failNext = false;
            throw new EmbeddingException("stub: the embedding endpoint is down");
        }
        List<float[]> vectors = new ArrayList<>(texts.size());
        for (String text : texts) {
            vectors.add(vectorFor(text));
        }
        return List.copyOf(vectors);
    }

    private float[] vectorFor(String text) {
        for (Function<String, float[]> rule : rules) {
            float[] assigned = rule.apply(text);
            if (assigned != null) {
                return assigned;
            }
        }
        return ONE;
    }

    private static float[] filled(float value) {
        float[] vector = new float[DIM];
        java.util.Arrays.fill(vector, value);
        return vector;
    }
}
