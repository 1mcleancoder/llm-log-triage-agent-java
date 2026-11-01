package i.m.clean.coder.triage;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.errors.AnthropicServiceException;
import com.anthropic.models.messages.*;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

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
public final class Stage2PromptOnly {

    private Stage2PromptOnly() {
    }

    record IncidentSummary(
            String severity,
            @JsonProperty("first_seen") String firstSeen,
            @JsonProperty("affected_services") List<String> affectedServices,
            @JsonProperty("root_cause_guess") String rootCauseGuess,
            List<String> evidence,
            @JsonProperty("suggested_action") String suggestedAction) {
    }

    public static void main(String[] args) throws Throwable {
        final Model model = args.length > 0 ? Model.of(args[0]) : Model.CLAUDE_HAIKU_5_5;
        final long maxTokens = args.length > 1 ? Long.parseLong(args[1]) : 400;

        // 1. read the log excerpt
        final LogFiles logs = new LogFiles(Path.of("data/logs"));
        final List<String> excerpt = logs.between("payments.log", "2026-10-25T14:02", "2026-10-25T16:10", 100);

        // 2. wrap the data in xml so that model can tell the data from instructions
//        final String userPrompt = "<logs file=\"payments.log\">\n"
//                + String.join("\n", excerpt) + "\n</logs>\n\n"
//                + "Payments started failing around 14:00. What is going wrong? ";
        final String userPrompt =
                tag(
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
                        """)
                .addUserMessage(userPrompt)
                .build();

        int thinkingChars = 0;
        int ansChars = 0;
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
                if (block.isThinking()) {
                    final int len = block.thinking().get().thinking().length();
                    thinkingChars += len;
                    log.info("[thinking] len: {}, total: {}", len, thinkingChars);
                } else if (block.isText()) {
                    String txt = block.text().get().text();
                    answerText.append(txt);
                    log.info("{}", txt);
                    ansChars += txt.length();
                }
            }

            usage = message.usage();
            if (message.stopReason().map(Objects::toString).orElse("none").equals("max_tokens")) {
                log.info("Max tokens limit reached!! so exiting");
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
            if (!List.of("high", "medium", "low", "unknown").contains(summary.severity)) {
                throw new IllegalStateException("Invalid severity: " + summary.severity);
            }

            String sent = String.join("\n", excerpt);
            for (String line : summary.evidence) {
                if (!sent.contains(line)) {
                    throw new IllegalStateException("Evidence not found in logs: " + line);
                }
            }
        } catch (AnthropicServiceException | IllegalStateException e) {
            log.info(e.getMessage());
            exception = e;
        } catch (JsonProcessingException e) {
            log.error("Could not parse model output as {} \n {}", IncidentSummary.class.getName(), json);
            exception = new IllegalStateException("Invalid model output", e);
        } finally {
            log.info("---- telemetry ----");
            log.info("model               = {}", message != null ? message.model() : "null");
            log.info("stop_reason         = {}", message != null ? message.stopReason().map(Object::toString).orElse("none") : "null");
            log.info("input_tokens        = {}", usage == null ? -1 : usage.inputTokens());
            log.info("output_tokens       = {}", usage == null ? -1 : usage.outputTokens());
            log.info("thinking_chars      = {}", thinkingChars);
            log.info("answer_chars        = {}", ansChars);
            log.info("latency_ms          = {}", latencyMs);
            log.info("excerpt_lines       = {}", excerpt.size());

            System.exit(exception != null ? 1 : 0);
        }
    }

    static String tag(String name, Map<String, String> attrs, String content) {
        String a = attrs.entrySet().stream()
                .map(e -> " " + e.getKey() + "=\"" + e.getValue() + "\"")
                .collect(Collectors.joining());
        return "<" + name + a + ">\n" + content + "\n</" + name + ">";
    }
}
