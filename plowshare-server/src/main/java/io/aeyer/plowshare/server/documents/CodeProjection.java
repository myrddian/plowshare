package io.aeyer.plowshare.server.documents;

import java.util.UUID;

/** Retained source and its syntax checkpoint, attached to an immutable information revision. */
public record CodeProjection(UUID revision, String text, CodeOutline outline) {}
