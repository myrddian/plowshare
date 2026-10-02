package io.aeyer.plowshare.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.aeyer.plowshare.protocol.frames.Envelope;
import java.io.IOException;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;
import okhttp3.*;

/** Read-only accounting frames over the authenticated client socket. No usage REST fallback. */
public final class UsageSocket implements AutoCloseable {
    public static final Set<String> REPORTS = Set.of("usage.conversation", "usage.project", "usage.agent", "usage.run",
            "usage.orchestration", "usage.models", "usage.pools");
    private static final Set<String> FRAMES;
    static {
        var frames = new HashSet<>(REPORTS);
        frames.addAll(List.of("usage.calls", "usage.subscribe", "usage.unsubscribe", "conversation.context.count"));
        FRAMES = Set.copyOf(frames);
    }
    public record Snapshot(JsonNode report, long revision, boolean stale, String error) { }
    private final OkHttpClient http;
    private final HttpUrl url;
    private final ObjectMapper json;
    private final Map<String, CompletableFuture<JsonNode>> pending = new ConcurrentHashMap<>();
    private final Map<String, View> subscriptions = new ConcurrentHashMap<>();
    private final Map<String, JsonNode> early = new ConcurrentHashMap<>();
    private final Set<View> views = ConcurrentHashMap.newKeySet();
    private volatile WebSocket socket;
    private volatile long generation;
    private volatile boolean closed;
    // One coalesced callback per bounded view; client reads never block OkHttp's socket reader.
    private final ExecutorService callbacks = Executors.newSingleThreadExecutor(task -> {
        var thread = new Thread(task, "plowshare-usage-views"); thread.setDaemon(true); return thread;
    });

    UsageSocket(OkHttpClient http, HttpUrl base, ObjectMapper json) throws IOException {
        this.http = http; this.json = json;
        this.url = base.newBuilder().addPathSegments("v1/events").addQueryParameter("session", UUID.randomUUID().toString()).build();
        connect();
    }
    /** Reconnect explicitly after a transport loss; only view reads/subscriptions are reissued. */
    public synchronized void reconnect() throws IOException {
        if (closed) throw new IOException("usage view is closed");
        disconnected();
        connect();
        for (View view : List.copyOf(views)) view.subscribe();
    }
    private void connect() throws IOException {
        long epoch = ++generation;
        var ready = new CompletableFuture<Void>();
        WebSocket next = http.newWebSocket(new Request.Builder().url(url).build(), new WebSocketListener() {
            @Override public void onOpen(WebSocket ws, Response response) { ready.complete(null); }
            @Override public void onMessage(WebSocket ws, String text) {
                if (epoch != generation) return;
                try {
                    JsonNode frame = json.readTree(text);
                    if (!Envelope.CURRENT_VERSION.equals(frame.path("protocol_version").asText())) return;
                    String id = frame.path("id").asText("");
                    if (!id.isEmpty()) {
                        var reply = pending.remove(id);
                        if (reply != null) reply.complete(frame.path("payload"));
                        return;
                    }
                    String type = frame.path("type").asText();
                    if (!type.equals("usage.updated") && !type.equals("usage.closed")) return;
                    JsonNode payload = frame.path("payload");
                    String subscription = payload.path("subscription").asText();
                    View view = subscriptions.get(subscription);
                    if (view != null) view.push(type, payload);
                    else if (!subscription.isEmpty() && (early.containsKey(subscription) || early.size() < 8))
                        early.compute(subscription, (key, held) -> held == null || type.equals("usage.closed")
                                || payload.path("revision").asLong(-1) > held.path("payload").path("revision").asLong(-1) ? frame : held);
                } catch (IOException invalid) { disconnected(); }
            }
            @Override public void onFailure(WebSocket ws, Throwable failure, Response response) {
                ready.completeExceptionally(new IOException("usage socket unavailable", failure));
                if (epoch == generation) disconnected();
            }
            @Override public void onClosing(WebSocket ws, int code, String reason) { ws.close(code, reason); }
            @Override public void onClosed(WebSocket ws, int code, String reason) { ready.completeExceptionally(new IOException("usage socket closed")); if (epoch == generation) disconnected(); }
        });
        socket = next;
        try { await(ready, Duration.ofSeconds(5)); }
        catch (IOException failure) { next.cancel(); socket = null; throw failure; }
    }
    private void disconnected() {
        WebSocket old = socket; socket = null;
        if (old != null) old.cancel();
        pending.values().forEach(f -> f.completeExceptionally(new IOException("usage socket lost; last snapshot may be stale")));
        pending.clear(); subscriptions.clear(); early.clear();
        views.forEach(View::stale);
    }
    public JsonNode request(String type, Map<String, ?> payload) throws IOException {
        if (!FRAMES.contains(type)) throw new IllegalArgumentException("unknown usage frame");
        WebSocket active = socket;
        if (active == null) throw new IOException("reconnect before reading usage");
        if (pending.size() >= 64) throw new IOException("too many pending usage reads");
        String id = UUID.randomUUID().toString();
        var answer = new CompletableFuture<JsonNode>(); pending.put(id, answer);
        try {
            if (!active.send(json.writeValueAsString(new Envelope(id, type, Envelope.CURRENT_VERSION, new LinkedHashMap<String, Object>(payload)))))
                throw new IOException("usage socket refused the request");
            JsonNode outcome = await(answer, Duration.ofSeconds(30));
            if (!outcome.path("code").asText().equals("OK")) throw new IOException(outcome.path("said").asText("usage request refused"));
            JsonNode result = outcome.path("payload");
            if (REPORTS.contains(type) && !matches(result, type, payload)) throw new IOException("unreadable usage report");
            if (!validReply(type,result,payload)) throw new IOException("unreadable usage response");
            return result.deepCopy();
        } finally { pending.remove(id); }
    }
    public synchronized View watch(String type, Map<String, ?> filter, Consumer<Snapshot> changed) throws IOException {
        if (!REPORTS.contains(type)) throw new IllegalArgumentException("usage watch needs a report");
        if (closed) throw new IOException("usage view is closed");
        if (views.size() >= 8) throw new IOException("at most eight usage views may be open");
        var selected = new LinkedHashMap<String,Object>(); filter.forEach((key,value) -> { if(value != null && !key.equals("cursor")) selected.put(key,value); });
        View view = new View(type, Map.copyOf(selected), Objects.requireNonNull(changed)); views.add(view);
        try { view.subscribe(); return view; } catch (IOException failure) { views.remove(view); throw failure; }
    }
    public final class View implements AutoCloseable {
        private final String type;
        private final Map<String, ?> filter;
        private final Consumer<Snapshot> changed;
        private String subscription;
        private long revision = -1;
        private JsonNode report;
        private boolean stopped;
        private final java.util.concurrent.atomic.AtomicReference<Snapshot> latest = new java.util.concurrent.atomic.AtomicReference<>();
        private final java.util.concurrent.atomic.AtomicBoolean publishing = new java.util.concurrent.atomic.AtomicBoolean();
        private View(String type, Map<String, ?> filter, Consumer<Snapshot> changed) {
            this.type = type; this.filter = filter; this.changed = changed;
        }
        private void subscribe() throws IOException {
            var payload = new LinkedHashMap<String, Object>(filter); payload.put("report_type", type);
            JsonNode initial = request("usage.subscribe", payload);
            if (!matches(initial.path("report"), type, filter) || !initial.path("filters").equals(initial.path("report").path("filters"))
                    || !initial.path("subscription").isTextual() || !validRevision(initial.path("revision")))
                throw new IOException("unreadable usage subscription");
            synchronized (this) {
                if (stopped) { request("usage.unsubscribe", Map.of("subscription",initial.path("subscription").asText())); return; }
                subscription = initial.path("subscription").asText(); revision = initial.path("revision").asLong();
                report = initial.path("report").deepCopy(); subscriptions.put(subscription, this);
                publish(new Snapshot(report.deepCopy(), revision, false, null));
                JsonNode held = early.remove(subscription);
                if (held != null) push(held.path("type").asText(), held.path("payload"));
            }
        }
        private synchronized void push(String type, JsonNode payload) {
            if (stopped) return;
            if (type.equals("usage.closed")) { stale(); subscriptions.remove(subscription); return; }
            if (!validRevision(payload.path("revision")) || payload.path("revision").asLong() <= revision) return;
            JsonNode next = payload.path("report");
            if (!validReport(next) || !next.path("filters").equals(report.path("filters"))) { stale(); return; }
            revision = payload.path("revision").asLong(); report = next.deepCopy();
            publish(new Snapshot(report.deepCopy(), revision, false, null));
        }
        private synchronized void stale() { if (!stopped) publish(new Snapshot(report == null ? null : report.deepCopy(), revision, true, "Usage unavailable; reconnect to reconcile.")); }
        private void publish(Snapshot snapshot) {
            latest.set(snapshot);
            if (closed || !publishing.compareAndSet(false, true)) return;
            try { callbacks.execute(() -> {
                try {
                    Snapshot next;
                    while ((next = latest.getAndSet(null)) != null) {
                        synchronized (this) { if(stopped || closed) return; }
                        try { changed.accept(next); } catch (RuntimeException ignored) { /* A view cannot break the socket. */ }
                    }
                } finally {
                    publishing.set(false);
                    Snapshot next = latest.get(); if(next != null) publish(next);
                }
            }); } catch (RejectedExecutionException closing) { publishing.set(false); }
        }
        @Override public void close() throws IOException {
            String id;
            synchronized (this) { if (stopped) return; stopped = true; id = subscription; }
            latest.set(null); views.remove(this);
            if (id != null) { subscriptions.remove(id); if (socket != null) request("usage.unsubscribe", Map.of("subscription", id)); }
        }
    }
    private boolean validReply(String type,JsonNode value,Map<String,?> requested) {
        if(REPORTS.contains(type)) return matches(value,type,requested);
        if(type.equals("usage.unsubscribe")) return value.isObject();
        if(type.equals("usage.subscribe")) return value.path("subscription").isTextual() && !value.path("subscription").asText().isEmpty()
                && validRevision(value.path("revision")) && validReport(value.path("report")) && value.path("filters").equals(value.path("report").path("filters"));
        if(type.equals("conversation.context.count")) {
            JsonNode count=value.path("count"),tokens=count.path("tokens");
            return Objects.equals(requested.get("conversation"),value.path("conversation").asText())
                    && Objects.equals(requested.get("agent"),value.path("agent").asText()) && value.path("projection").asText().equals("next")
                    && Set.of("MEASURED","ESTIMATED","UNKNOWN").contains(count.path("basis").asText())
                    && (tokens.isNull() || tokens.isTextual() && tokens.asText().matches("\\d+")) && count.path("gaps").isArray();
        }
        if(!type.equals("usage.calls") || !value.path("filters").path("type").asText().equals(type)
                || !value.path("filters").path("filter").isObject() || !value.path("health").path("watermark").isTextual()
                || !(value.path("cursor").isNull() || value.path("cursor").isTextual())) return false;
        if(value.path("calls").isArray()) {
            for(JsonNode call:value.path("calls")) if(!call.path("call_id").isTextual() || !call.path("wire_model").isTextual()
                    || !call.path("operation").isTextual() || !call.path("attempts_truncated").isBoolean() || !validAttempts(call.path("attempts"))) return false;
            return true;
        }
        return value.path("call").isTextual() && Objects.equals(requested.get("call"),value.path("call").asText()) && validAttempts(value.path("attempts"));
    }
    private static boolean validAttempts(JsonNode values) {
        if(!values.isArray()) return false;
        for(JsonNode attempt:values) {
            if(!attempt.path("attempt_id").isTextual() || !attempt.path("attempt_number").isIntegralNumber()
                    || attempt.path("attempt_number").asLong() < 1 || !attempt.path("cost_kind").isTextual()) return false;
            for(String key:List.of("input_tokens","output_tokens","provider_total_tokens","cache_read_tokens","cache_write_tokens","reasoning_tokens")) {
                JsonNode token=attempt.path(key); if(!token.isNull() && !(token.isTextual() && token.asText().matches("\\d+"))) return false;
            }
        }
        return true;
    }
    private static boolean validRevision(JsonNode revision) { return revision.isIntegralNumber() && revision.canConvertToLong() && revision.asLong() >= 0; }
    private boolean matches(JsonNode report, String type, Map<String,?> filter) {
        if(!validReport(report) || !type.equals(report.path("filters").path("type").asText())) return false;
        JsonNode resolved = report.path("filters").path("filter");
        return filter.entrySet().stream().allMatch(entry -> {
            if(entry.getValue() == null || entry.getKey().equals("cursor")) return true;
            JsonNode actual = resolved.path(entry.getKey());
            if(entry.getKey().equals("from") || entry.getKey().equals("to")) {
                try { return java.time.Instant.parse(actual.asText()).equals(java.time.Instant.parse(entry.getValue().toString())); }
                catch(RuntimeException invalid) { return false; }
            }
            return actual.equals(json.valueToTree(entry.getValue()));
        });
    }
    static boolean validReport(JsonNode value) {
        if (!value.isObject() || !value.path("filters").isObject() || !REPORTS.contains(value.path("filters").path("type").asText())
                || !value.path("filters").path("filter").isObject() || !value.path("groups").isArray() || !value.path("health").path("watermark").isTextual()
                || !value.path("health").path("capture_enabled").isBoolean() || !value.path("health").path("as_of").isTextual()) return false;
        JsonNode totals = value.path("totals");
        for (String field : List.of("calls", "attempts", "input_tokens", "output_tokens", "active_calls", "incomplete_attempts", "unknown_cost_attempts", "input_tokens_known", "output_tokens_known"))
            if (!totals.path(field).isTextual() || !totals.path(field).asText().matches("\\d+")) return false;
        if (!totals.path("costs").isObject()) return false;
        for (JsonNode cost : totals.path("costs")) if(!cost.isTextual() || !cost.asText().matches("\\d+(?:\\.\\d+)?")) return false;
        for (JsonNode row : value.path("groups")) {
            var child = value.deepCopy(); ((com.fasterxml.jackson.databind.node.ObjectNode)child).set("totals",row);
            ((com.fasterxml.jackson.databind.node.ObjectNode)child).set("groups", new ObjectMapper().createArrayNode());
            if(!validReport(child)) return false;
        }
        return totals.path("complete").isBoolean() && totals.path("usage_complete").isBoolean() && totals.path("cost_complete").isBoolean();
    }
    private static <T> T await(CompletableFuture<T> future, Duration timeout) throws IOException {
        try { return future.get(timeout.toMillis(), TimeUnit.MILLISECONDS); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new IOException("usage read interrupted", interrupted); }
        catch (TimeoutException timedOut) { throw new IOException("usage read timed out", timedOut); }
        catch (ExecutionException failure) { throw new IOException("usage read unavailable", failure.getCause()); }
    }
    @Override public synchronized void close() {
        if (closed) return; closed = true; ++generation;
        callbacks.shutdownNow(); views.clear(); subscriptions.clear(); early.clear();
        WebSocket active = socket; socket = null;
        if (active != null) active.close(1000, "usage view closed");
        pending.values().forEach(f -> f.completeExceptionally(new IOException("usage view closed"))); pending.clear();
    }
}
