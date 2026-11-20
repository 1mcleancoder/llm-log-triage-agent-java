package i.m.clean.coder.triage;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.models.messages.*;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.util.*;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;

@Slf4j
public class Stage4Accuracy {
  public static void main(String[] args) throws JsonProcessingException {
    final long runs = (args.length > 0) ? Long.parseLong(args[0]) : 3;
    final Model model = (args.length > 1) ? Model.of(args[1]) : Model.CLAUDE_HAIKU_5_5;
    final long maxTokens = (args.length > 2) ? Long.parseLong(args[2]) : 4096;
    final long maxInputTokens = (args.length > 3) ? Long.parseLong(args[3]) : 250_000;
    final double costUsd = (args.length > 4) ? Double.parseDouble(args[4]) : 0.03;

    final LogFiles logFiles = new LogFiles(Path.of("data/logs"));

    AnthropicClient client = AnthropicOkHttpClient.fromEnv();

    final List<RunResult> results = new ArrayList<>((int) runs);
    for (int i = 0; i < runs; i++) {
      log.atDebug().log(
          "\n\nRun # {}: ------------------------------------------------------------------------",
          i);
      final RunResult result = runOnce(client, logFiles, model, maxTokens, maxInputTokens, costUsd);
      log.atDebug().log(
          "\n\nOutput is: ------\n{}\n------",
          new ObjectMapper().writerWithDefaultPrettyPrinter().writeValueAsString(result.summary));
      TriageUtils.printTelemetry(result.telemetries);
      results.add(result);
    }

    printResults(results);

    client.close();
  }

  private static void printResults(List<RunResult> results) {
    double totalTurns = 0;
    double totalCost = 0;
    int triggerFound = 0;
    int numBudget = 0;

    for (RunResult result : results) {
      totalTurns += result.turns;
      totalCost += result.cost;
      triggerFound += result.isTriggerFound() ? 1 : 0;
      numBudget += result.endedBy != EndedBy.end_turn ? 1 : 0;

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

  public static String getSearchEntriesSummary(List<String> hits) {
    Map<String, Integer> countByFileName = new HashMap<>();
    for (String hit : hits) {
      String fileName = hit.substring(0, hit.indexOf(':'));
      countByFileName.merge(fileName, 1, Integer::sum);
    }

    StringBuilder summary = new StringBuilder();
    int i = 0;
    for (Map.Entry<String, Integer> entry : countByFileName.entrySet()) {
      summary.append(entry.getKey()).append(" ").append(entry.getValue());
      if (i != countByFileName.size() - 1) {
        summary.append(", ");
      }
    }

    return summary.toString();
  }

  private static String runTool(ToolUseBlock tool, final LogFiles logFiles) {
    switch (tool.name()) {
      case "list_files":
        log.atDebug().log("[tool_use: name={}]", tool.name());
        return String.join(",", logFiles.listFiles());

      case "search_logs":
        record SearchArgs(String regex, String file, int maxResults) {}
        final SearchArgs args = tool._input().convert(SearchArgs.class);
        //        int cap = Math.min(args.maxResults, 50);
        List<String> hits = logFiles.search(args.regex(), args.file(), args.maxResults);
        boolean truncated = hits.size() > args.maxResults;
        String body =
            String.join("\n", hits.subList(0, Math.min(args.maxResults + 1, hits.size())));
        log.atDebug().log(
            "[tool_use: name={}, SearchArgs={}, truncated={}]", tool.name(), args, truncated);
        return truncated
            ? body
                + "\n[truncated: "
                + hits.size()
                + " matches - "
                + getSearchEntriesSummary(hits)
                + "; narrow the regex or pick a file]"
            : body;

      case "read_lines":
        record ReadLineArgs(String file, int from, int to, int maxLines) {}
        final ReadLineArgs rlArgs = tool._input().convert(ReadLineArgs.class);
        List<String> readLines =
            logFiles.readLines(rlArgs.file, rlArgs.from, rlArgs.to, rlArgs.maxLines);
        log.atDebug().log(
            "[tool_use: name={}, args={}, matchingLines.size()={}]",
            tool.name(),
            rlArgs,
            readLines.size());
        return String.join("\n", readLines);

      case "count_matches":
        record CountArgs(String regex, String file, String from, String to, String bktInterval) {}
        final CountArgs cArgs = tool._input().convert(CountArgs.class);
        String matches =
            logFiles.countMatches(cArgs.regex, cArgs.file, cArgs.from, cArgs.to, cArgs.bktInterval);
        log.atDebug().log(
            "[tool_use: name={}, args={}, matchingLines.size()={}]",
            tool.name(),
            cArgs,
            matches.length() > 20 ? matches.substring(0, 20) + " ... " : matches);
        return matches;
    }
    throw new IllegalArgumentException("Unrecognized tool: %s".formatted(tool.name()));
  }

  private static String constructSystemMessage() {
    return """
    You are an experienced site reliability engineer triaging a production incident.
    Base every claim on the logs provided.

    Correlate the information as much as you want like follow the trace id from across different files
    eg. trace=1a2b3c4dsfs is there in all the files payments.log, gateway.log, orders.log, auth.log.

    Look for plausible triggers like deploy, traffic or load changes, configuration changes and check
    against the logs. Look at INFO lines as well as errors in the minutes before the problem started happening.

    For each candidate trigger, run at least one check before concluding. For a traffic or load hypothesis, compare
    request counts per interval before and after the onset using count_matches.

    Every item in "evidence" must be a verbatim log line. Do not add interpretation there.
    "suggested_action" should be at-most two sentences.
    <examples>
    <example>
    <logs file="auth.log">
    2026-09-12T08:15:02.114Z ERROR [auth] [auth-worker-3] trace=a1b2 ERR-2307 DB connection refused host=auth-db-1
    2026-09-12T08:15:02.890Z ERROR [auth] [auth-worker-5] trace=c3d4 ERR-2307 DB connection refused host=auth-db-1
    </logs>
    <answer>
    {"severity": "HIGH", "first_seen": "2026-09-12T08:15:02.114Z", "affected_services": ["auth"],
     "root_cause_guess": "auth-db-1 is refusing connections",
     "evidence": ["2026-09-12T08:15:02.114Z ERROR [auth] [auth-worker-3] trace=a1b2 ERR-2307 DB connection refused host=auth-db-1"],
     "suggested_action": "Check auth-db-1 health and connection limits.", "metrics": ["orders/5min: 13:50 ≈ 250 → 13:55 ≈ 900"]}
    </answer>
    </example>
    <example>
    <logs file="orders.log">
    2026-09-12T10:00:01.002Z INFO [orders] [http-1] trace=e5f6 order confirmed order_id=ord-1 status=201
    </logs>
    <answer>
    {"severity": "UNKNOWN", "first_seen": null, "affected_services": [], "root_cause_guess": "unknown",
     "evidence": [], "suggested_action": "No errors in these logs; provide logs from the reported time window.",
     "metrics": []}
    </answer>
    </example>
    </examples>
    """;
  }

  record RunResult(
      boolean answered,
      EndedBy endedBy, // "end_turn", "budget", "turn_limit", "error"
      IncidentSummary summary,
      int turns,
      long inputTokens,
      double cost,
      long latencyMs,
      List<Telemetry> telemetries) {

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
  }

  enum EndedBy {
    end_turn,
    budget,
    time_overrun,
    turn_limit,
    error
  }

  static RunResult runOnce(
      AnthropicClient client,
      LogFiles logFiles,
      Model model,
      long maxTokens,
      long maxInputTokens,
      double maxCost) {
    StructuredMessage<IncidentSummary> message = null;
    long totalInTokens = 0;
    long totalOutTokens = 0;
    long totalThinkingTokens = 0;
    double totalCost = 0;
    long latencyMs = 0;

    List<Telemetry> telemetries = new ArrayList<>();

    final StructuredMessageCreateParams.Builder<IncidentSummary> conversation =
        MessageCreateParams.builder()
            .model(model)
            .maxTokens(maxTokens)
            .system(constructSystemMessage())
            .addUserMessage("Payments started failing around 14:00. What is going wrong?")
            .addTool(ToolUtils.createReadLinesTool())
            .addTool(ToolUtils.createListFilesTool())
            .addTool(ToolUtils.createSearchLogsTool())
            .addTool(ToolUtils.createCountMatchesTool())
            .outputConfig(IncidentSummary.class);
    final int MAX_TURNS = 12;
    boolean budgetReached = false;
    EndedBy endReason = EndedBy.end_turn;
    for (int i = 0; i < MAX_TURNS + 1; i++) {
      log.atDebug().log("Turn {}/{}: ", (i + 1), MAX_TURNS);

      long start = System.nanoTime();
      message = client.messages().create(conversation.build());
      latencyMs += (System.nanoTime() - start) / 1_000_000;

      Usage usage = message.usage();
      totalInTokens += usage.inputTokens();
      totalOutTokens += usage.outputTokens();
      totalThinkingTokens +=
          usage.outputTokensDetails().map(OutputTokensDetails::thinkingTokens).orElse(0L);
      totalCost += ModelPricing.getInstance().cost(message.model(), usage).orElse(0);

      final String stopReason = message.stopReason().map(Object::toString).orElse("none");

      telemetries.add(
          new Telemetry(
              message.model().toString(),
              message.stopReason().map(Objects::toString).orElse("none"),
              new Telemetry.Counts(usage.inputTokens(), totalInTokens),
              new Telemetry.Counts(usage.outputTokens(), totalOutTokens),
              new Telemetry.Counts(
                  usage.outputTokensDetails().map(OutputTokensDetails::thinkingTokens).orElse(0L),
                  totalThinkingTokens),
              latencyMs,
              totalCost));

      if (budgetReached || stopReason.equals(StopReason.END_TURN.asString())) {
        IncidentSummary summary =
            message.content().stream()
                .flatMap(block -> block.text().stream())
                .map(StructuredTextBlock::text)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("No structured output returned"));
        return budgetReached
            ? new RunResult(
                true, endReason, summary, i + 1, totalInTokens, totalCost, latencyMs, telemetries)
            : new RunResult(
                true,
                EndedBy.end_turn,
                summary,
                i + 1,
                totalInTokens,
                totalCost,
                latencyMs,
                telemetries);
      }

      if (stopReason.equals(StopReason.MAX_TOKENS.asString())) {
        return new RunResult(
            false, EndedBy.budget, null, i + 1, totalInTokens, totalCost, latencyMs, telemetries);
      }

      if (stopReason.equals(StopReason.TOOL_USE.asString())) {
        conversation.addMessage(message.rawMessage());
        List<ContentBlockParam> userBlocks = new ArrayList<>(processToolBlocks(logFiles, message));

        boolean outOfTurns = (i + 1) >= MAX_TURNS;
        boolean overBudget = totalCost >= maxCost || totalInTokens >= maxInputTokens;
        if (outOfTurns || overBudget) { // the model wants more; we say "answer now"
          budgetReached = true;
          endReason = outOfTurns ? EndedBy.turn_limit : EndedBy.budget;
          userBlocks.add(ContentBlockParam.ofText("Budget reached. Give your final answer now..."));
          conversation.toolChoice(ToolChoiceNone.builder().build());

          log.atDebug().log(
              "outOfTurns={} (({} + 1) >= MAX_TURNS({})), overBudget={} (totalCost >= maxCost: {} >= {} = {}) || (totalInTokens >= maxInputTokens: {} >= {} = {})",
              outOfTurns,
              i,
              MAX_TURNS,
              overBudget,
              totalCost,
              maxCost,
              totalCost >= maxCost,
              totalInTokens,
              maxInputTokens,
              totalInTokens >= maxInputTokens);
        }

        conversation.addUserMessageOfContentBlockParams(userBlocks);
      }
    }

    return new RunResult(
        false,
        EndedBy.turn_limit,
        null,
        MAX_TURNS + 1,
        totalInTokens,
        totalCost,
        latencyMs,
        telemetries);
  }

  @NotNull
  private static List<ContentBlockParam> processToolBlocks(
      final LogFiles logFiles, final StructuredMessage<IncidentSummary> message) {
    return message.content().stream()
        .flatMap(block -> block.toolUse().stream())
        .parallel()
        .map(
            toolUse -> {
              ToolResultBlockParam.Builder resultBuilder =
                  ToolResultBlockParam.builder().toolUseId(toolUse.id());
              try {
                resultBuilder.content(runTool(toolUse, logFiles));
              } catch (Exception e) {
                resultBuilder.content("Error: " + e.getMessage()).isError(true);
              }

              return ContentBlockParam.ofToolResult(resultBuilder.build());
            })
        .toList();
  }
}
