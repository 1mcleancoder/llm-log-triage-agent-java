package i.m.clean.coder.triage;

import com.anthropic.models.messages.*;
import java.util.Map;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class TriageUtils {
  private TriageUtils() {}

  public static String tag(String name, Map<String, String> attrs, String content) {
    String a =
        attrs.entrySet().stream()
            .map(e -> " " + e.getKey() + "=\"" + e.getValue() + "\"")
            .collect(Collectors.joining());
    return "<" + name + a + ">\n" + content + "\n</" + name + ">";
  }

  public static void printUsage(
      Usage usage, Model model, String stopReason, long latencyMs, int numExcerptLines) {
    long thinkingTokens =
        (usage == null
            ? 0
            : usage.outputTokensDetails().map(OutputTokensDetails::thinkingTokens).orElse(0L));
    double cost = usage == null ? 0 : ModelPricing.getInstance().cost(model, usage).orElse(0);
    printUsage(
        usage == null ? -1 : usage.inputTokens(),
        usage == null ? -1 : usage.outputTokens(),
        thinkingTokens,
        cost,
        model,
        stopReason,
        latencyMs,
        numExcerptLines);
  }

  public static void printUsage(
      long inTokens,
      long outTokens,
      long thinkingTokens,
      double cost,
      Model model,
      String stopReason,
      long latencyMs,
      int numExcerptLines) {
    log.info("---- telemetry ----");
    log.info("model               = {}", model != null ? model : "null");
    log.info("stop_reason         = {}", stopReason);
    log.info("input_tokens        = {}", inTokens);
    log.info("output_tokens       = {}", outTokens);
    log.info("thinking_tokens     = {}", thinkingTokens);
    log.info("answer_tokens       = {}", outTokens - thinkingTokens);
    log.info("latency_ms          = {}", latencyMs);
    log.info("excerpt_lines       = {}", numExcerptLines);
    log.info("cost                = {}", cost > 0 ? "%.6f".formatted(cost) : "UNKNOWN");
  }

  public static void printUsage(
      long turnInput,
      long totalInTokens,
      long turnOutTokens,
      long totalOutTokens,
      long turnThinkingTokens,
      long totalThinkingTokens,
      double cost,
      Model model,
      String stopReason,
      long latencyMs,
      int numExcerptLines) {
    log.info("---- telemetry ----");
    log.info("model                     = {}", model != null ? model : "null");
    log.info("stop_reason               = {}", stopReason);
    log.info("input_tokens    [turn]    = {}", turnInput);
    log.info("input_tokens    [total]   = {}", totalInTokens);
    log.info("output_tokens   [turn]    = {}", turnOutTokens);
    log.info("output_tokens   [total]   = {}", totalOutTokens);
    log.info("thinking_tokens [turn]    = {}", turnThinkingTokens);
    log.info("thinking_tokens [total]   = {}", totalThinkingTokens);
    log.info("answer_tokens             = {}", turnOutTokens - turnThinkingTokens);
    log.info("latency_ms                = {}", latencyMs);
    log.info("excerpt_lines             = {}", numExcerptLines);
    log.info("cost                      = {}", cost > 0 ? "%.6f".formatted(cost) : "UNKNOWN");
  }
}
