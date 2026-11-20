package i.m.clean.coder.triage;

import java.util.List;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

@Slf4j
record RunResult(
    boolean answered,
    Stage4Accuracy.EndedBy endedBy, // "end_turn", "budget", "turn_limit", "error"
    @Getter IncidentSummary summary,
    int turns,
    long inputTokens,
    double cost,
    long latencyMs,
    @Getter List<Telemetry> telemetries) {

  public boolean isTriggerFound() {
    if (summary == null || summary.rootCauseGuess() == null) {
      return false;
    }
    final String guess = summary.rootCauseGuess().toLowerCase();
    return guess.contains("flash-oct25") || guess.contains("campaign");
  }

  public String toString() {
    return "answered=%s, endedBy=%s, foundTrigger(summary)=%s, turns=%s, cost=%s"
        .formatted(answered, endedBy, isTriggerFound(), turns, String.format("%.4f", cost));
  }

  public static void printResults(List<RunResult> results) {
    double totalTurns = 0;
    double totalCost = 0;
    int triggerFound = 0;
    int numBudget = 0;

    for (RunResult result : results) {
      totalTurns += result.turns;
      totalCost += result.cost;
      triggerFound += result.isTriggerFound() ? 1 : 0;
      numBudget += result.endedBy != Stage4Accuracy.EndedBy.end_turn ? 1 : 0;

      log.info(result.toString());
    }

    log.info(
        "trigger found {}/{} | avg {} turns | total ${} | {} cut short by budget",
        triggerFound,
        results.size(),
        String.format("%.2f", totalTurns / results.size()),
        String.format("%.2f", totalCost),
        numBudget == 0 ? "none" : numBudget);
  }
}
