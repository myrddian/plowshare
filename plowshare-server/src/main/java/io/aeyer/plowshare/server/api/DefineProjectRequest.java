package io.aeyer.plowshare.server.api;

import java.util.List;

/**
 * The body of {@code POST /v1/projects}.
 *
 * @param name the project. The same string a memory's {@code project} names —
 *     one tier, one place — and refused rather than stripped if it carries
 *     edge whitespace, which {@code ProjectStore.named} argues at length
 * @param workspace the directory on the <b>server's</b> disk this project sits
 *     in. Absolute or relative to the server's working directory, and resolved
 *     once, when it is defined. It is the project's place and not the whole of
 *     what its jobs reach — see {@code lent}
 * @param lent further directories on the same disk its jobs may also reach.
 *     {@code null} means none, on {@code exclusions}' reasoning below. <b>Not
 *     required to be inside the workspace</b>: a directory elsewhere is what
 *     lending a second one means, and the bound is the mandatory exclusions
 *     rather than containment. Unlike an exclusion, each must already be a
 *     directory on this server, and the refusal names which one is not —
 *     {@code ProjectStore.defineLending} argues both halves
 * @param exclusions extra paths, inside any of those roots, that they may not
 *     reach. {@code null} means none: an omitted key is how a caller says "fence
 *     off nothing extra", and it must not read as the null the store refuses.
 *     The ones no project may override are never sent here and cannot be dropped
 *     from here — see {@code ProjectStore.mandatoryExclusions}
 */
public record DefineProjectRequest(
        String name, String workspace, List<String> lent, List<String> exclusions) {}
