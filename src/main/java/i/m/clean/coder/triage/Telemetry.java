package i.m.clean.coder.triage;

import java.util.List;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public record Telemetry(
    String model,
    String stopReason,
    Counts inputTokens,
    Counts outputTokens,
    Counts thinkingTokens,
    long latencyMs,
    double cost) {

  record Counts(long value, long total) {}

  public static void printTelemetry(List<Telemetry> telemetries) {
    if (telemetries.isEmpty()) return;

    String[] headers = {
      "turn",
      "model",
      "stop",
      "in_tok",
      "in_%",
      "out_tok",
      "out_%",
      "think_tok",
      "think_%",
      "latency_ms",
      "cost"
    };

    String[][] rows = new String[telemetries.size()][headers.length];
    for (int i = 0; i < telemetries.size(); i++) {
      Telemetry t = telemetries.get(i);
      Telemetry prev = i > 0 ? telemetries.get(i - 1) : null;
      rows[i][0] = String.valueOf(i + 1);
      rows[i][1] = t.model() != null ? t.model() : "null";
      rows[i][2] = t.stopReason() != null ? t.stopReason() : "null";
      rows[i][3] = t.inputTokens().value() + "/" + t.inputTokens().total();
      rows[i][4] = pct(prev != null ? prev.inputTokens().value() : -1, t.inputTokens().value());
      rows[i][5] = t.outputTokens().value() + "/" + t.outputTokens().total();
      rows[i][6] = pct(prev != null ? prev.outputTokens().value() : -1, t.outputTokens().value());
      rows[i][7] = t.thinkingTokens().value() + "/" + t.thinkingTokens().total();
      rows[i][8] =
          pct(prev != null ? prev.thinkingTokens().value() : -1, t.thinkingTokens().value());
      rows[i][9] = String.valueOf(t.latencyMs());
      rows[i][10] = t.cost() > 0 ? "%.6f".formatted(t.cost()) : "unknown";
    }

    Telemetry last = telemetries.get(telemetries.size() - 1);
    long totalLatency = telemetries.stream().mapToLong(Telemetry::latencyMs).sum();
    double totalCost = telemetries.stream().mapToDouble(Telemetry::cost).sum();
    String[] totals = {
      "TOTAL",
      "-",
      "-",
      String.valueOf(last.inputTokens().total()),
      "-",
      String.valueOf(last.outputTokens().total()),
      "-",
      String.valueOf(last.thinkingTokens().total()),
      "-",
      String.valueOf(totalLatency),
      totalCost > 0 ? "%.6f".formatted(totalCost) : "unknown"
    };

    int[] widths = new int[headers.length];
    for (int c = 0; c < headers.length; c++) {
      widths[c] = headers[c].length();
      for (String[] row : rows) widths[c] = Math.max(widths[c], row[c].length());
      widths[c] = Math.max(widths[c], totals[c].length());
    }

    StringBuilder fmt = new StringBuilder();
    for (int w : widths) fmt.append("%-").append(w).append("s  ");
    String format = fmt.toString();

    StringBuilder sep = new StringBuilder();
    for (int w : widths) sep.append("-".repeat(w)).append("  ");

    StringBuilder sb = new StringBuilder("\nTelemetry is: ------\n");
    sb.append(format.formatted((Object[]) headers)).append("\n");
    sb.append(sep).append("\n");
    for (String[] row : rows) sb.append(format.formatted((Object[]) row)).append("\n");
    sb.append(sep).append("\n");
    sb.append(format.formatted((Object[]) totals)).append("\n");

    log.info(sb.toString());
  }

  private static String pct(long prev, long curr) {
    if (prev <= 0) return "-";
    return "%+.1f%%".formatted((curr - prev) * 100.0 / prev);
  }
}
