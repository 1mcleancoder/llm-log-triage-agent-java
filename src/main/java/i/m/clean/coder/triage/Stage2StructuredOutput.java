package i.m.clean.coder.triage;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.models.messages.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@Slf4j
public class Stage2StructuredOutput {
    public static void main(String[] args) {
        // 1. Construct model from args and set its max tokens
        final Model model = (args.length > 0) ? Model.of(args[0]) : Model.CLAUDE_HAIKU_5_5;
        final long maxTokens = (args.length > 1) ? Long.parseLong(args[1]) : 2048;

        // 2. Construct API instance
        final LogFiles logs = new LogFiles(Path.of("data/logs"));
        final List<String> excerpt = logs.between("payments.log", "2026-10-25T12:02", "2026-10-25T13:10", 100);

        final String userMessage =
                TriageUtils.tag(
                        "logs",
                        Map.of("file", "payments.log"),
                        String.join("\n", excerpt))
                        + "\n\n Payments started failing around 14:00. What is going wrong? ";
        log.info("User Message is: -------first 200 chars only\n{}...\n-------", userMessage.substring(0, 200));
        final String systemPrompt = constructSystemMessage();
        log.info("System Prompt is: -------\n{}\n-------", systemPrompt);
        // 3. Create message with relevant parameters like systemPrompt, userMessage etc
        StructuredMessageCreateParams<IncidentSummary> params =
                MessageCreateParams.builder()
                        .model(model)
                        .maxTokens(maxTokens)
                        .thinking(ThinkingConfigDisabled.builder().build())
                        .system(systemPrompt)
                        .addUserMessage(userMessage)
                        .outputConfig(IncidentSummary.class)
                        .build();

        final String rawSchema = params.rawParams().outputConfig().flatMap(OutputConfig::format).map(JsonOutputFormat::schema).map(Objects::toString).orElse("none");
        log.info("Schema sent estimated tokens: {} \n{}", rawSchema.length()/3, rawSchema);

        // 4. Create the api and call the message
        Usage usage = null;
        StructuredMessage<IncidentSummary> message = null;
        Throwable exception = null;
        long latencyMs = 0;
        try {
            AnthropicClient client = AnthropicOkHttpClient.fromEnv();
            final long start = System.nanoTime();
            message = client.messages().create(params);
            latencyMs = (System.nanoTime() - start) / 1_000_000;

            usage = message.usage();
            final String stop = message.stopReason().map(Objects::toString).orElse("none");
            if (!stop.equals("end_turn")) {
                throw new IllegalStateException("Unexpected stop_reason: %s".formatted(stop));
            }

            IncidentSummary summary =
                    message.content().stream()
                            .flatMap(block -> block.text().stream())
                            .map(StructuredTextBlock::text)
                            .findFirst().orElseThrow(() -> new IllegalStateException("No structured output returned"));

            final String sent = String.join("\n", excerpt);
            for (String line : summary.evidence()) {
                if (!sent.contains(line)) {
                    throw new IllegalStateException("Evidence \n%s not found in logs: %s".formatted(line, excerpt));
                }
            }
            log.info("\n\nOutput is: ------\n{}\n------", new ObjectMapper().writerWithDefaultPrettyPrinter().writeValueAsString(summary));
        } catch (Throwable e) {
            exception = e;
            log.error(e.getMessage(), e);
        } finally {
            TriageUtils.printUsage(usage, message == null ? null : message.model(), message == null ? null : message.stopReason().toString(), latencyMs, excerpt.size());
            System.exit(exception != null ? 1 : 0);
        }
    }

    private static String constructSystemMessage() {
        return """
                You are an experienced site reliability engineer triaging a production incident.
                Base every claim on the logs provided.
                
                Every item in "evidence" must be a verbatim log line. Do not add interpretation there.
                "suggested_action" should be at-most two sentences.
                <examples>
                <example>
                <logs file="auth.log">
                2026-09-12T08:15:02.114Z ERROR [auth] [auth-worker-3] trace=a1b2 ERR-2307 DB connection refused host=auth-db-1
                2026-09-12T08:15:02.890Z ERROR [auth] [auth-worker-5] trace=c3d4 ERR-2307 DB connection refused host=auth-db-1
                </logs>
                <answer>
                {"severity": "high", "first_seen": "2026-09-12T08:15:02.114Z", "affected_services": ["auth"],
                 "root_cause_guess": "auth-db-1 is refusing connections",
                 "evidence": ["2026-09-12T08:15:02.114Z ERROR [auth] [auth-worker-3] trace=a1b2 ERR-2307 DB connection refused host=auth-db-1"],
                 "suggested_action": "Check auth-db-1 health and connection limits."}
                </answer>
                </example>
                <example>
                <logs file="orders.log">
                2026-09-12T10:00:01.002Z INFO [orders] [http-1] trace=e5f6 order confirmed order_id=ord-1 status=201
                </logs>
                <answer>
                {"severity": "unknown", "first_seen": null, "affected_services": [], "root_cause_guess": "unknown",
                 "evidence": [], "suggested_action": "No errors in these logs; provide logs from the reported time window."}
                </answer>
                </example>
                </examples>
                """;
    }
}
