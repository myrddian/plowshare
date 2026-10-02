package io.aeyer.plowshare.protocol.search;

/** A provider saying it is alive, and what it is. */
public record ProviderHealth(String status, String version) {

    public static ProviderHealth up(String version) {
        return new ProviderHealth("UP", version);
    }
}
