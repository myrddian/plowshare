package io.aeyer.plowshare.server.harness;

import io.aeyer.plowshare.server.llm.accounting.UsageAttribution;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.aeyer.plowshare.server.agents.AgentDefinition;
import io.aeyer.plowshare.server.agents.CallValidator;
import io.aeyer.plowshare.server.llm.dispatch.ChatMessage;
import io.aeyer.plowshare.server.llm.dispatch.ChatRequest;
import io.aeyer.plowshare.server.llm.dispatch.Completion;
import io.aeyer.plowshare.server.llm.dispatch.Deltas;
import io.aeyer.plowshare.server.llm.dispatch.LlmDispatcher;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/**
 * The call validator (spec 2026-09-28-call-failures §5): one model call in the stuck advisor's
 * shape -- the {@code call_validator} agent's prompt as the system message, a brief of the held
 * reply as the user message, no tools, nothing spent from the run's budget -- on the agent's
 * {@code model:}, {@code system.validator}, which {@code plowshare.llm.system-overrides.validator}
 * binds ({@code SYSTEM_VALIDATOR_MODEL}; unset, the system model).
 *
 * <p><b>Streamed, though nothing reads the deltas</b>, for {@code StuckTrapFactory.advise}'s
 * reason: a stream is asked whether it is abandoned at every chunk, so a verdict nobody will read
 * -- timed out, or the run cancelled -- gives its inference slot back.
 */
public final class ModelCallValidator implements CallValidator, AutoCloseable {

    public static final String AGENT = "call_validator";

    /** How long a run waits for a verdict before warning as it would have anyway. */
    static final Duration TIMEOUT = Duration.ofSeconds(60);

    private static final ObjectMapper JSON = new ObjectMapper();

    private final LlmDispatcher dispatcher;
    private final Supplier<AgentDefinition> definition;
    private final Duration timeout;
    private final ExecutorService calls = Executors.newVirtualThreadPerTaskExecutor();

    /**
     * @param definition the {@code call_validator} agent, resolved per consult, so an operator's
     *     edit to the file reaches the next one
     */
    public ModelCallValidator(LlmDispatcher dispatcher, Supplier<AgentDefinition> definition) {
        this(dispatcher, definition, TIMEOUT);
    }

    ModelCallValidator(LlmDispatcher dispatcher, Supplier<AgentDefinition> definition,
            Duration timeout) {
        this.dispatcher = dispatcher;
        this.definition = definition;
        this.timeout = timeout;
    }

    @Override
    public Verdict validate(Question question, BooleanSupplier cancelled) {
        AgentDefinition validator = definition.get();
        ChatRequest request = ChatRequest.of(validator.model(),
                        List.of(ChatMessage.system(validator.prompt()),
                                ChatMessage.user(brief(question))))
                .withSampling(validator.sampling())
                .withAttribution(question.usage().forOperation(
                        UsageAttribution.Operation.REVIEW, validator.name()));
        CompletableFuture<Completion> call = new CompletableFuture<>();
        calls.execute(() -> {
            if (call.isDone()) {
                return;
            }
            // Throwable, not RuntimeException: an Error escaping the call would otherwise end
            // this task with the future still open, and the run's thread would wait out the whole
            // timeout for a verdict that can never come. The runtime still sees it as a failed
            // consult -- validate wraps whatever is not a RuntimeException.
            try {
                call.complete(dispatcher.stream(request, Deltas.DISCARDING,
                        () -> call.isDone() || cancelled.getAsBoolean()));
            } catch (Throwable failed) {
                call.completeExceptionally(failed);
            }
        });
        try {
            // orTimeout completes this very future, which the stream's abandoned check reads.
            return Verdict.parse(
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

    /** What the validator is shown: the request, the tool and its schema, and the held reply. */
    static String brief(Question question) {
        String schema;
        try {
            schema = JSON.writerWithDefaultPrettyPrinter()
                    .writeValueAsString(question.tool().parameters());
        } catch (JsonProcessingException unwritable) {
            schema = String.valueOf(question.tool().parameters());
        }
        return "The request the other model was working on:\n<request>\n"
                + question.request().strip() + "\n</request>\n\n"
                + "The tool: " + question.tool().name() + "\n"
                + "Its parameters, as a JSON Schema:\n" + schema + "\n\n"
                + "The other model's reply, which made no tool call:\n<reply>\n"
                + question.reply().strip() + "\n</reply>";
    }

    @Override
    public void close() {
        calls.shutdownNow();
    }
}
