package io.aeyer.plowshare.protocol.search;

/** One result. Three fields, because a model reads three fields. */
public record Hit(String url, String title, String snippet) {}
