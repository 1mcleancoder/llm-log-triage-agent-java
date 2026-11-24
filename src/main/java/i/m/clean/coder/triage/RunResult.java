package i.m.clean.coder.triage;

import java.util.List;
import java.util.Set;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

@Slf4j
record RunResult(
    boolean answered,
    EndedBy endedBy, // "end_turn", "budget", "turn_limit", "error"
    @Getter IncidentSummary summary,
    int turns,
    long inputTokens,
    double cost,
    long latencyMs,
    int repeats,
    @Getter List<Telemetry> telemetries) {

  /** Level 1: traffic surge AND pool saturation both described */
  public boolean foundMechanism() {
    if (summary == null) return false;
    String g = summary.rootCauseGuess().toLowerCase();
    boolean load = g.matches("(?s).*(traffic|surge|spike|burst|volume|quadrupl|4x).*");
    boolean pool = g.contains("pool") && g.matches("(?s).*(saturat|20/20|exhaust|full).*");
    return load && pool;
  }

  /** Level 2: found the actual trigger line, not just the word "campaign" from the 14:40 note */
  public boolean foundTrigger() {
    if (summary == null || summary.rootCauseGuess() == null) {
      return false;
    }
    return summary.evidence().stream().anyMatch(e -> e.contains("campaign FLASH-OCT25 activated"));
  }

  /** Level 3: every evidence line exists verbatim in the logs */
  public List<String> fabricatedEvidence(Set<String> allLines) {
    if (summary == null) return List.of();
    return summary.evidence().stream()
        .filter(e -> !allLines.contains(LogFiles.extractLogLine(e).strip()))
        .toList();
  }

  /** Overall pass: found the trigger and made nothing up */
  public boolean passed(boolean triggerFound, List<String> fabricatedEvidences) {
    return triggerFound && fabricatedEvidences.isEmpty();
  }

  public String toPrettyString() {
    StringBuilder sb = new StringBuilder();
    sb.append("answered     : ").append(answered).append("\n");
    sb.append("endedBy      : ").append(endedBy).append("\n");
    sb.append("turns        : ").append(turns).append("\n");
    sb.append("inputTokens  : ").append(inputTokens).append("\n");
    sb.append("cost         : ").append(String.format("%.6f", cost)).append("\n");
    sb.append("latencyMs    : ").append(latencyMs).append("\n");
    if (summary == null) {
      sb.append("summary      : null\n");
    } else {
      sb.append("summary:\n");
      sb.append("  severity         : ").append(summary.severity()).append("\n");
      sb.append("  firstSeen        : ").append(summary.firstSeen()).append("\n");
      sb.append("  affectedServices :\n");
      summary.affectedServices().forEach(s -> sb.append("    - ").append(s).append("\n"));
      sb.append("  rootCauseGuess   : ").append(summary.rootCauseGuess()).append("\n");
      sb.append("  suggestedAction  : ").append(summary.suggestedAction()).append("\n");
      sb.append("  evidence         :\n");
      summary.evidence().forEach(e -> sb.append("    - ").append(e).append("\n"));
      sb.append("  metrics          :\n");
      summary.metrics().forEach(m -> sb.append("    - ").append(m).append("\n"));
    }
    return sb.toString();
  }

  public void logPretty() {
    log.info("\n{}", toPrettyString());
  }

  public boolean isAnswerCutShort() {
    if (summary == null || summary.rootCauseGuess() == null) {
      return false;
    }

    return summary.rootCauseGuess().contains("Budget reached");
  }

  public void printResult(Set<String> logLines) {
    log.info("Pass/Fail:");
    log.info("=".repeat(120));
    final boolean foundMech = foundMechanism();
    final boolean triggerFound = foundTrigger();
    List<String> fabricatedEvidences = fabricatedEvidence(logLines);
    final boolean isPass = passed(triggerFound, fabricatedEvidences);
    log.info(
        "answered={}, endedBy={}, turns={}, cost={} | mechanism={} trigger={} grounded={} | {} | repeats {}\n",
        answered,
        endedBy,
        turns,
        String.format("%.4f", cost),
        foundMech ? "Y" : "N",
        triggerFound ? "Y" : "N",
        fabricatedEvidences.isEmpty() ? "Y" : "N",
        isPass ? "PASS" : "FAIL",
        repeats);
    log.info("=".repeat(120));
  }

  public static void printResults(List<RunResult> results, Set<String> logLines) {
    int numBudget = 0;
    int countPass = 0;
    int countMechanism = 0;
    int countTriggerFound = 0;
    int countGrounded = 0;
    double countTurns = 0;
    int countCutshort = 0;
    double totalInTokens = 0;
    double totalCost = 0;
    int countRepeats = 0;

    log.info("=".repeat(120));
    int i = 0;
    for (RunResult result : results) {
      final boolean foundMech = result.foundMechanism();
      countMechanism += foundMech ? 1 : 0;

      countTurns += result.turns;
      totalInTokens += result.inputTokens;
      totalCost += result.cost;
      final boolean triggerFound = result.foundTrigger();
      countTriggerFound += triggerFound ? 1 : 0;
      numBudget += result.endedBy == EndedBy.end_turn ? 1 : 0;

      List<String> fabricatedEvidences = result.fabricatedEvidence(logLines);
      countGrounded += fabricatedEvidences.isEmpty() ? 1 : 0;
      final boolean isPass = result.passed(triggerFound, fabricatedEvidences);
      countPass += isPass ? 1 : 0;

      final boolean isCutShort = result.isAnswerCutShort();
      countCutshort += isCutShort ? 1 : 0;

      countRepeats += result.repeats;
      log.info(
          "run={}, answered={}, endedBy={}, turns={}, cost={} | mechanism={} trigger={} grounded={} | {}\n",
          ++i,
          result.answered,
          result.endedBy,
          result.turns,
          String.format("%.4f", result.cost),
          foundMech ? "Y" : "N",
          triggerFound ? "Y" : "N",
          fabricatedEvidences.isEmpty() ? "Y" : "N",
          isPass ? "PASS" : "FAIL");
    }

    log.info("Summary: ");
    log.info("=".repeat(120));
    log.info(
        "PASS {}/{} | mechanism {}/{} | trigger {}/{} | grounded {}/{} | avg {} turns | avg {} in tokens | avg $ ${}/run | avg $ per PASS {} | withinBudget {}/{} | cut short {} | repeats {}",
        countPass,
        results.size(),
        countMechanism,
        results.size(),
        countTriggerFound,
        results.size(),
        countGrounded,
        results.size(),
        String.format("%.1f", countTurns / results.size()),
        "~" + Math.round(totalInTokens / results.size() / 1000.0) + "K",
        String.format("%.3f", totalCost / results.size()),
        String.format("%.3f", totalCost / countPass),
        numBudget,
        results.size(),
        countCutshort,
        countRepeats);
    log.info("=".repeat(120));
  }
}
