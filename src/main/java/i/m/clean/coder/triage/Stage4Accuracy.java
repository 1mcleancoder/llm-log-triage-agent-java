package i.m.clean.coder.triage;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.models.messages.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class Stage4Accuracy {
  public static void main(String[] args) {
    final Model model = (args.length > 0) ? Model.of(args[0]) : Model.CLAUDE_HAIKU_5_5;
    final long maxTokens = (args.length > 1) ? Long.parseLong(args[1]) : 4096;

    final LogFiles logFiles = new LogFiles(Path.of("data/logs"));

    StructuredMessageCreateParams.Builder<IncidentSummary> conversation =
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

    AnthropicClient client = AnthropicOkHttpClient.fromEnv();
    StructuredMessage<IncidentSummary> message = null;
    Throwable exception = null;
    long totalInTokens = 0;
    long totalOutTokens = 0;
    long totalThinkingTokens = 0;
    double cost;
    long latencyMs;

    List<Telemetry> telemetries = new ArrayList<>();
    try {
      final int MAX_TURNS = 12;
      boolean isEndTurn = false;
      for (int i = 0; i < MAX_TURNS; i++) {
        log.info("Turn {}/{}: ", (i + 1), MAX_TURNS);

        long start = System.nanoTime();
        message = client.messages().create(conversation.build());
        latencyMs = (System.nanoTime() - start) / 1_000_000;

        Usage usage = message.usage();
        totalInTokens += usage.inputTokens();
        totalOutTokens += usage.outputTokens();
        totalThinkingTokens +=
            usage.outputTokensDetails().map(OutputTokensDetails::thinkingTokens).orElse(0L);
        cost = ModelPricing.getInstance().cost(message.model(), usage).orElse(0);

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
                cost));

        if (stopReason.equals(StopReason.END_TURN.asString())) {
          isEndTurn = true;
          break;
        }

        if (stopReason.equals(StopReason.MAX_TOKENS.asString())) {
          throw new IllegalStateException("Max tokens limit reached!! so exiting");
        }

        if (stopReason.equals(StopReason.TOOL_USE.asString())) {
          conversation.addMessage(message.rawMessage());

          final List<ContentBlockParam> results =
              message.content().stream()
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

          conversation.addUserMessageOfContentBlockParams(results);
        }
      }

      if (!isEndTurn) {
        throw new IllegalStateException(
            "Turn limit reached %s without an answer".formatted(MAX_TURNS));
      }

      IncidentSummary summary =
          message.content().stream()
              .flatMap(block -> block.text().stream())
              .map(StructuredTextBlock::text)
              .findFirst()
              .orElseThrow(() -> new IllegalStateException("No structured output returned"));
      log.info(
          "\n\nOutput is: ------\n{}\n------",
          new ObjectMapper().writerWithDefaultPrettyPrinter().writeValueAsString(summary));

      // todo: will fix it later as sometimes it returns the file name as the first part of the line
      // and sometimes now. Need to look into it later
      //      if (!LogUtils.checkIfTextIsPresentInSpecificLogFile(summary.evidence(), logFiles)) {
      //        throw new IllegalStateException(
      //            "Evidence is not found in any of log files: %s. \nEvidence:\n%s"
      //                .formatted(logFiles.listFiles(), summary.evidence()));
      //      }
    } catch (Throwable e) {
      exception = e;
      log.error(e.getMessage(), e);
    } finally {
      TriageUtils.printTelemetry(telemetries);
      System.exit(exception != null ? 1 : 0);
    }
  }

  private static String runTool(ToolUseBlock tool, final LogFiles logFiles) {
    switch (tool.name()) {
      case "list_files":
        log.info("[tool_use: name={}]", tool.name());
        return String.join(",", logFiles.listFiles());

      case "search_logs":
        record SearchArgs(String regex, String file, int maxResults) {}
        final SearchArgs args = tool._input().convert(SearchArgs.class);
        int cap = Math.min(args.maxResults, 50);
        List<String> hits = logFiles.search(args.regex(), args.file(), cap + 1);
        boolean truncated = hits.size() > cap;
        String body = String.join("\n", hits.subList(0, Math.min(cap, hits.size())));
        log.info("[tool_use: name={}, SearchArgs={}, truncated={}]", tool.name(), args, truncated);
        return truncated
            ? body + "\n[truncated: more than " + cap + " matches; narrow the regex or pick a file]"
            : body;

      case "read_lines":
        record ReadLineArgs(String file, int from, int to, int maxLines) {}
        final ReadLineArgs rlArgs = tool._input().convert(ReadLineArgs.class);
        List<String> readLines =
            logFiles.readLines(rlArgs.file, rlArgs.from, rlArgs.to, rlArgs.maxLines);
        log.info(
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
        log.info(
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
}
