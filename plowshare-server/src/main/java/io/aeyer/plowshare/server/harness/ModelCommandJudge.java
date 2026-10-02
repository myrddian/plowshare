package io.aeyer.plowshare.server.harness;

import io.aeyer.plowshare.server.llm.accounting.*;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.aeyer.plowshare.server.agents.AgentDefinition;
import io.aeyer.plowshare.server.llm.dispatch.ChatMessage;
import io.aeyer.plowshare.server.llm.dispatch.ChatRequest;
import io.aeyer.plowshare.server.llm.dispatch.Completion;
import io.aeyer.plowshare.server.llm.dispatch.Deltas;
import io.aeyer.plowshare.server.llm.dispatch.LlmDispatcher;
import io.aeyer.plowshare.server.orchestrations.CommandJudge;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The command judge (V67): one model call in {@link ModelCallValidator}'s shape — the {@code
 * command_judge} agent's prompt as the system message, the commands as the user message, no
 * tools, nothing spent from the run's budget — on the agent's {@code model:}, {@code
 * system.judge}, which {@code plowshare.llm.system-overrides.judge} binds ({@code
 * SYSTEM_JUDGE_MODEL}; unset, the system model).
 *
 * <p><b>It fails closed.</b> Only {@code {"clear": true}} is clear; a failure, a timeout, or an
 * answer with no JSON object, or whose {@code clear} is not a JSON boolean, is thrown, and the
 * caller asks the person ({@link CommandJudge#safely}).
 */
public final class ModelCommandJudge implements CommandJudge, AutoCloseable, UsageAware {
    private UsageOwners usageOwners = UsageOwners.NONE;
    @Override public void useUsageOwners(UsageOwners source) { usageOwners = java.util.Objects.requireNonNull(source); }


    public static final String AGENT = "command_judge";

    /** How long a caller waits for a verdict before it asks the person instead. */
    static final Duration TIMEOUT = Duration.ofSeconds(60);

    /** How much of the judge's one line is kept: it is shown to the person and in the record. */
    static final int WHY_KEPT = 300;

    private static final ObjectMapper JSON = new ObjectMapper();

    private final LlmDispatcher dispatcher;
    private final Supplier<AgentDefinition> definition;
    private final Duration timeout;
    private final ExecutorService calls = Executors.newVirtualThreadPerTaskExecutor();

    /**
     * @param definition the {@code command_judge} agent, resolved per consult, so an operator's
     *     edit to the file reaches the next one
     */
    public ModelCommandJudge(LlmDispatcher dispatcher, Supplier<AgentDefinition> definition) {
        this(dispatcher, definition, TIMEOUT);
    }

    ModelCommandJudge(LlmDispatcher dispatcher, Supplier<AgentDefinition> definition,
            Duration timeout) {
        this.dispatcher = dispatcher;
        this.definition = definition;
        this.timeout = timeout;
    }

    @Override
    public Verdict judge(List<Command> commands) {
        return judge(commands, usageOwners.in(io.aeyer.plowshare.protocol.Home.global(), null, UsageAttribution.Operation.REVIEW));
    }

public Verdict judge(List<Command> commands, UsageAttribution owner) {
        if (commands.isEmpty()) {
            throw new IllegalArgumentException("the judge is shown at least one command");
        }
        AgentDefinition judge = definition.get();
        ChatRequest request = ChatRequest.of(judge.model(),
                        List.of(ChatMessage.system(judge.prompt()),
                                ChatMessage.user(brief(commands))))
                .withSampling(judge.sampling()).withAttribution(owner.forOperation(UsageAttribution.Operation.REVIEW,judge.name()));
        CompletableFuture<Completion> call = new CompletableFuture<>();
        calls.execute(() -> {
            if (call.isDone()) {
                return;
            }
            // Throwable, not RuntimeException: ModelCallValidator's own reason.
            try {
                call.complete(dispatcher.stream(request, Deltas.DISCARDING,
                        () -> call.isDone()));
            } catch (Throwable failed) {
                call.completeExceptionally(failed);
            }
        });
        try {
            return parse(
                    call.orTimeout(timeout.toMillis(), TimeUnit.MILLISECONDS).join().content());
        } catch (CompletionException failed) {
            Throwable cause = failed.getCause() == null ? failed : failed.getCause();
            if (cause instanceof TimeoutException) {
                throw new IllegalStateException("no verdict within " + timeout.toMillis() + " ms");
            }
            throw cause instanceof RuntimeException runtime
                    ? runtime : new IllegalStateException(cause);
        }
    }

    /**
     * What the judge is shown: each command in its own block, its argv as a JSON array, so an
     * argument's spaces and quotes are exactly what runs. All of it is the run's text, so a {@code
     * <command>} or {@code </command>} written into it is neutralised: left as it is, an argument
     * could close its block early and have what follows read as the brief's own.
     */
    static String brief(List<Command> commands) {
        StringBuilder brief = new StringBuilder("The commands, " + commands.size() + " in all:\n");
        for (Command command : commands) {
            brief.append("<command>\n")
                    .append("argv: ").append(neutral(json(command.argv()))).append('\n')
                    .append("stdin: ").append(command.stdin() == null ? "(none)"
                            : neutral(json(command.stdin()))).append('\n')
                    .append("directory: ").append(neutral(command.cwd())).append('\n')
                    .append("side: ").append(command.side()).append('\n')
                    .append("</command>\n");
        }
        return brief.toString().stripTrailing();
    }

    private static final Pattern FENCE_TAG =
            Pattern.compile("<(/?)\\s*(command)\\s*>", Pattern.CASE_INSENSITIVE);

    /** Every {@code <command>} and {@code </command>} in {@code text}, in any case, with its
     *  angle brackets swapped for {@code ‹ ›}. */
    static String neutral(String text) {
        return FENCE_TAG.matcher(text).replaceAll(found -> Matcher.quoteReplacement(
                "‹" + found.group(1) + found.group(2) + "›"));
    }

    private static String json(Object value) {
        try {
            return JSON.writeValueAsString(value);
        } catch (JsonProcessingException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    /**
     * @param content the model's answer
     * @return its verdict: clear only for a JSON {@code true}
     * @throws IllegalStateException when the answer is not {@code {"clear": <boolean>, ...}}
     */
    static Verdict parse(String content) {
        String body = content == null ? "" : content.strip();
        int open = body.indexOf('{');
        int close = body.lastIndexOf('}');
        if (open < 0 || close < open) {
            throw new IllegalStateException("the judge answered no JSON object");
        }
        JsonNode node;
        try {
            node = JSON.readTree(body.substring(open, close + 1));
        } catch (JsonProcessingException unreadable) {
            throw new IllegalStateException("the judge's answer is not JSON", unreadable);
        }
        JsonNode clear = node.path("clear");
        if (!clear.isBoolean()) {
            throw new IllegalStateException("the judge's answer has no 'clear' true or false");
        }
        JsonNode why = node.path("why");
        return new Verdict(clear.booleanValue(), why.isTextual() ? oneLine(why.asText()) : null);
    }

    /** The first non-blank line, cut at {@link #WHY_KEPT}; null for none. */
    private static String oneLine(String text) {
        String first = text.lines().map(String::strip).filter(each -> !each.isEmpty())
                .findFirst().orElse(null);
        if (first == null) {
            return null;
        }
        return first.length() <= WHY_KEPT ? first : first.substring(0, WHY_KEPT - 1) + "…";
    }

    @Override
    public void close() {
        calls.shutdownNow();
    }
}
