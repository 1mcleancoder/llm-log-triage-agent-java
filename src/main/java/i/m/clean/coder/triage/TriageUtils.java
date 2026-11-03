package i.m.clean.coder.triage;

import com.anthropic.models.messages.*;
import lombok.extern.slf4j.Slf4j;

import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
public class TriageUtils {
    private TriageUtils() {}

    public static String tag(String name, Map<String, String> attrs, String content) {
        String a = attrs.entrySet().stream()
                .map(e -> " " + e.getKey() + "=\"" + e.getValue() + "\"")
                .collect(Collectors.joining());
        return "<" + name + a + ">\n" + content + "\n</" + name + ">";
    }

    public static void printUsage(Usage usage, Model model, String stopReason, long latencyMs, int numExceptLines) {
        long thinkingTokens = (usage == null ? 0 : usage.outputTokensDetails().map(OutputTokensDetails::thinkingTokens).orElse(0L));
        log.info("---- telemetry ----");
        log.info("model               = {}", model != null ? model : "null");
        log.info("stop_reason         = {}", stopReason);
        log.info("input_tokens        = {}", usage == null ? -1 : usage.inputTokens());
        log.info("output_tokens       = {}", usage == null ? -1 : usage.outputTokens());
        log.info("thinking_tokens     = {}", thinkingTokens);
        log.info("answer_tokens       = {}", usage == null ? 0 : (usage.outputTokens() - thinkingTokens));
        log.info("latency_ms          = {}", latencyMs);
        log.info("excerpt_lines       = {}", numExceptLines);
        log.info("cost                = {}", usage == null ? "NA" : ModelPricing.getInstance().cost(model, usage).stream().mapToObj("%.6f"::formatted).findFirst().orElse("UNKNOWN"));
    }
}
