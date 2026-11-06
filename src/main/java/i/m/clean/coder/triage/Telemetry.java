package i.m.clean.coder.triage;

public record Telemetry(
    String model,
    String stopReason,
    Counts inputTokens,
    Counts outputTokens,
    Counts thinkingTokens,
    long latencyMs,
    double cost) {

  record Counts(long value, long total) {}
}
