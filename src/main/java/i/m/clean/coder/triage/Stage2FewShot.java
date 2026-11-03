package i.m.clean.coder.triage;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.errors.AnthropicServiceException;
import com.anthropic.models.messages.*;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Stage 2: return the answer as a JSON incident summary your code can parse.
 * <p>
 * Your job. Suggested steps:
 * 1. Copy the request-building code from Stage1FirstCall.
 * 2. Define the target shape (see the README for the fields).
 * 3. Try the approaches in order and compare:
 * a) prompt only: describe the JSON in the system prompt, parse the reply;
 * b) add one or two examples (few-shot);
 * c) use structured outputs with a JSON schema
 * (see StructuredOutputsExample in the SDK's example module).
 * 4. Parse the reply into a Java record and fail loudly if it does not fit.
 * <p>
 * Break it: ask for the JSON but give the model a log excerpt with no errors.
 * What does it put in root_cause? Fix the prompt so it says "unknown" instead of guessing.
 */
@Slf4j
public final class Stage2FewShot {

    private Stage2FewShot() {
    }

    public static void main(String[] args) {
        final Model model = args.length > 0 ? Model.of(args[0]) : Model.CLAUDE_HAIKU_5_5;
        final long maxTokens = args.length > 1 ? Long.parseLong(args[1]) : 2048;

        // 1. read the log excerpt
        final LogFiles logs = new LogFiles(Path.of("data/logs"));
        final List<String> excerpt = logs.between("payments.log", "2026-10-25T14:02", "2026-10-25T16:10", 100);

        // 2. wrap the data in xml so that model can tell the data from instructions
//        final String userPrompt = "<logs file=\"payments.log\">\n"
//                + String.join("\n", excerpt) + "\n</logs>\n\n"
//                + "Payments started failing around 14:00. What is going wrong? ";
        final String userPrompt =
                TriageUtils.tag(
                        "logs",
                        Map.of("file", "payments.log"),
                        String.join("\n", excerpt))
                        + "\n\n Payments started failing around 14:00. What is going wrong? ";

        AnthropicClient client = AnthropicOkHttpClient.fromEnv();
        MessageCreateParams params = MessageCreateParams.builder()
                .maxTokens(maxTokens)
                .model(model)
                .thinking(ThinkingConfigParam.ofDisabled(ThinkingConfigDisabled.builder().build()))
                .system("""
                        You are an experienced site reliability engineer triaging a production incident.
                        Base every claim on the logs provided.
                        Return ONLY a JSON object matching this shape. No markdown fences, no prose before or after.
                        Every item in "evidence" must be a verbatim log line. Do not add interpretation there.
                        "suggested_action" should be at-most two sentences.
                        {
                            "severity": "high | medium | low | unknown",
                            "first_seen": "ISO timestamp or null",
                            "affected_services": ["..."],
                            "root_cause_guess": "string or unknown",
                            "evidence": ["verbatim log line"],
                            "suggested_action": "string"
                        }
                        
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
                        """)
                .addUserMessage(userPrompt)
                .build();

        long latencyMs = 0;
        Message message = null;
        Usage usage = null;
        String json = "";
        Throwable exception = null;
        try {
            final long start = System.nanoTime();
            message = client.messages().create(params);
            latencyMs = (System.nanoTime() - start) / 1_000_000;
            final StringBuilder answerText = new StringBuilder();
            for (var block : message.content()) {
                if (block.isText() && block.text().isPresent()) {
                    String txt = block.text().get().text();
                    answerText.append(txt);
                    log.info("{}", txt);
                }
            }

            usage = message.usage();
            if (message.stopReason().map(Objects::toString).orElse("none").equals("max_tokens")) {
                throw new IllegalStateException("Max tokens limit reached!! so exiting");
            }

            json =
                    answerText.toString()
                            .strip()
                            .replaceFirst("^```(json)?\\s*", "")
                            .replaceFirst("\\s*```$", "");
            log.info("json: {}", json);

            ObjectMapper mapper = new ObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, true);
            IncidentSummary summary = mapper.readValue(json, IncidentSummary.class);

            String sent = String.join("\n", excerpt);
            for (String line : summary.evidence()) {
                if (!sent.contains(line)) {
                    throw new IllegalStateException("Evidence not found in logs: " + line);
                }
            }
        } catch (AnthropicServiceException | IllegalStateException e) {
            log.error("{}", e.getMessage());
            exception = e;
        } catch (JsonProcessingException e) {
            log.error("Could not parse model output as {} \n {}", IncidentSummary.class.getName(), json);
            exception = new IllegalStateException("Invalid model output", e);
        } finally {
            TriageUtils.printUsage(usage, message == null ? null : message.model(), message == null ? null : message.stopReason().toString(), latencyMs, excerpt.size());
            System.exit(exception != null ? 1 : 0);
        }
    }
}
