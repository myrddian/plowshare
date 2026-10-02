package io.aeyer.plowshare.server.ws;

import java.util.Map;

/**
 * One area of the application's contribution to the routing table — every
 * frame type one controller's worth of endpoints answers, with the handler
 * that claims each.
 *
 * <h2>A file per area, because the alternative is one signature per frame type</h2>
 *
 * <p>The table used to be assembled by a single {@code @Bean} method taking
 * one constructor parameter per frame type. Two pilots made that a
 * three-parameter method and it read perfectly well; the breadth plan adds
 * about fifty more types across seven tasks, which would have made it a
 * fifty-parameter method that <b>every one of those tasks edits</b> — a
 * guaranteed conflict seven ways, and a signature change that breaks every
 * parity test written before it, since each of them calls that method
 * positionally to prove its own type is registered.
 *
 * <p>Splitting it per area means a breadth task <b>adds a file</b>: one class
 * implementing this interface, holding its own area's literal map and taking
 * its own area's services in its own constructor. No shared signature, nothing
 * for a sibling task to conflict with, and a parity test that names only its
 * own area stays green when a sibling adds a type.
 *
 * <h2>Still a hand-built map, which is the part that must not be lost</h2>
 *
 * <p>{@link FrameRouter}'s own argument, kept: a frame {@code type} is a flat
 * string and not a sealed hierarchy with an annotation to reflect over, so the
 * mapping from type to handler is written out as a literal — here rather than
 * in one central class, but still as something a reader can open and read
 * without running the application. <b>What is discovered by scan is the set of
 * areas, not the contents of any of them</b>: Spring collects every {@code
 * FrameArea} bean into {@link FrameRoutingConfig}'s list, and each one names
 * its own types in source. The whole table is the union of the {@code
 * *Frames.java} files in this package, which is one directory listing.
 *
 * <p><b>An implementation must be a bean</b> — {@code @Component} on the class
 * — or Spring will not collect it and its types will simply not route, with no
 * error anywhere. {@code FrameShapeTest} finds implementations by type rather
 * than by annotation precisely so that it can fail on one that forgot.
 */
public interface FrameArea {

    /**
     * This area's types, keyed by the dotted discriminator a frame's {@code
     * type} names.
     *
     * <p>Every key is checked against {@link FrameTypes#requireWellFormed} by
     * {@link FrameRouter}'s constructor, and no two areas may claim the same
     * type — {@link FrameRoutingConfig} refuses to build a router if they do.
     *
     * @return a map from frame type to the handler that answers it, never null
     */
    Map<String, FrameHandler> frames();
}
