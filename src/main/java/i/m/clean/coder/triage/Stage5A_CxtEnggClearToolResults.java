package i.m.clean.coder.triage;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.models.messages.*;
import com.fasterxml.jackson.core.JsonProcessingException;
import java.nio.file.Path;
import java.util.*;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class Stage5A_CxtEnggClearToolResults {
  public static void main(String[] args) throws JsonProcessingException {
    final long runs = (args.length > 0) ? Long.parseLong(args[0]) : 3;
    final Model model = (args.length > 1) ? Model.of(args[1]) : Model.CLAUDE_HAIKU_5_5;
    final long maxTokens = (args.length > 2) ? Long.parseLong(args[2]) : 4096;
    final long maxInputTokens = (args.length > 3) ? Long.parseLong(args[3]) : 250_000;
    final double costUsd = (args.length > 4) ? Double.parseDouble(args[4]) : 0.03;

    final LogFiles logFiles = new LogFiles(Path.of("data/logs"));

    AnthropicClient client = AnthropicOkHttpClient.fromEnv();

    final Set<String> logLines = logFiles.getAllLogLines();
    final ContextStrategy strategy = new ClearOld(3);
    final List<RunResult> results = new ArrayList<>((int) runs);
    for (int i = 0; i < runs; i++) {
      log.info("=".repeat(80));
      log.info("Run # {}: ", i + 1);
      log.info("=".repeat(80));
      final RunResult result =
          runOnce(client, logFiles, model, strategy, maxTokens, maxInputTokens, costUsd);
      log.info("=".repeat(80));
      log.info("Output: ");
      log.info("=".repeat(80));
      log.info("\n{}\n", result.toPrettyString());
      result.printResult(logLines);
      Telemetry.printTelemetry(result.getTelemetries());
      results.add(result);
    }

    RunResult.printResults(results, logLines);

    client.close();
  }

  private static RunResult runOnce(
      AnthropicClient client,
      LogFiles logFiles,
      Model model,
      ContextStrategy strategy,
      long maxTokens,
      long maxInputTokens,
      double maxCost) {
    StructuredMessage<IncidentSummary> message;
    long totalInTokens = 0;
    long totalOutTokens = 0;
    long totalThinkingTokens = 0;
    double totalCost = 0;
    long latencyMs = 0;
    int repeats = 0;

    Set<String> seenToolCalls = new HashSet<>();
    List<Telemetry> telemetries = new ArrayList<>();

    MessageParam firstUser =
        MessageParam.ofUser("Payments started failing around 14:00. What is going wrong?");

    final StructuredMessageCreateParams.Builder<IncidentSummary> params =
        MessageCreateParams.builder()
            .model(model)
            .maxTokens(maxTokens)
            .system(Stage4AccuracyHelper.constructSystemMessage())
            .addTool(ToolUtils.createReadLinesTool())
            .addTool(ToolUtils.createListFilesTool())
            .addTool(ToolUtils.createSearchLogsTool())
            .addTool(ToolUtils.createCountMatchesTool())
            .outputConfig(IncidentSummary.class);

    List<Step> history = new ArrayList<>();

    final int MAX_TURNS = 12;
    boolean budgetReached = false;
    EndedBy endReason = EndedBy.end_turn;
    for (int i = 0; i < MAX_TURNS + 1; i++) {
      log.atDebug().log("Turn {}/{}: ", (i + 1), MAX_TURNS);

      long start = System.nanoTime();
      params.messages(strategy.build(firstUser, history, i + 1));
      message = client.messages().create(params.build());

      final long turnLatency = (System.nanoTime() - start) / 1_000_000;
      latencyMs += turnLatency;

      Usage usage = message.usage();
      totalInTokens += usage.inputTokens();
      totalOutTokens += usage.outputTokens();

      final long turnThinkingTokens =
          usage.outputTokensDetails().map(OutputTokensDetails::thinkingTokens).orElse(0L);
      totalThinkingTokens += turnThinkingTokens;
      final double turnCost = ModelPricing.getInstance().cost(message.model(), usage).orElse(0);
      totalCost += turnCost;

      final String stopReason = message.stopReason().map(Object::toString).orElse("none");

      telemetries.add(
          new Telemetry(
              message.model().toString(),
              message.stopReason().map(Objects::toString).orElse("none"),
              new Telemetry.Counts(usage.inputTokens(), totalInTokens),
              new Telemetry.Counts(usage.outputTokens(), totalOutTokens),
              new Telemetry.Counts(turnThinkingTokens, totalThinkingTokens),
              new Telemetry.Counts(turnLatency, latencyMs),
              new Telemetry.Counts((long) (turnCost * 1_000_000), (long) (totalCost * 1_000_000))));

      if (budgetReached || stopReason.equals(StopReason.END_TURN.asString())) {
        IncidentSummary summary =
            message.content().stream()
                .flatMap(block -> block.text().stream())
                .map(StructuredTextBlock::text)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("No structured output returned"));
        return budgetReached
            ? new RunResult(
                true,
                endReason,
                summary,
                i + 1,
                totalInTokens,
                totalCost,
                latencyMs,
                repeats,
                telemetries)
            : new RunResult(
                true,
                EndedBy.end_turn,
                summary,
                i + 1,
                totalInTokens,
                totalCost,
                latencyMs,
                repeats,
                telemetries);
      }

      if (stopReason.equals(StopReason.MAX_TOKENS.asString())) {
        return new RunResult(
            false,
            EndedBy.max_tokens,
            null,
            i + 1,
            totalInTokens,
            totalCost,
            latencyMs,
            repeats,
            telemetries);
      }

      if (stopReason.equals(StopReason.TOOL_USE.asString())) {
        List<ToolCall> toolCalls = new ArrayList<>(processToolBlocks(logFiles, message));

        String note = null;
        boolean outOfTurns = (i + 1) >= MAX_TURNS;
        boolean overBudget = totalCost >= maxCost || totalInTokens >= maxInputTokens;
        if (outOfTurns || overBudget) { // the model wants more; we say "answer now"
          budgetReached = true;
          endReason = outOfTurns ? EndedBy.turn_limit : EndedBy.budget;
          note = "Budget reached. Give your final answer now.";
          params.toolChoice(ToolChoiceNone.builder().build());

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

        for (ToolCall c : toolCalls) {
          if (!seenToolCalls.add(c.name() + c.args())) {
            repeats++;
            log.atDebug().log("REPEAT {}({})", c.name(), c.args());
          }
        }

        history.add(new Step(i+1, message.rawMessage().toParam(), toolCalls, note));
      }
    }

    return new RunResult(
        false,
        EndedBy.error,
        null,
        MAX_TURNS + 1,
        totalInTokens,
        totalCost,
        latencyMs,
        repeats,
        telemetries);
  }

  private static List<ToolCall> processToolBlocks(
      final LogFiles logFiles, final StructuredMessage<IncidentSummary> message) {
    return message.content().stream()
        .flatMap(block -> block.toolUse().stream())
        .parallel()
        .map(
            t -> {
              try {
                return new ToolCall(
                    t.id(), t.name(), t._input().toString(), ToolUtils.runTool(t, logFiles), false);
              } catch (Exception e) {
                return new ToolCall(
                    t.id(), t.name(), t._input().toString(), "Error: " + e.getMessage(), true);
              }
            })
        .toList();
  }

  interface ContextStrategy {
    List<MessageParam> build(MessageParam firstUser, List<Step> history, int currentTurn);
  }

  record Step(int turn, MessageParam assistant, List<ToolCall> calls, String note) {
    MessageParam toolResultsMsg(boolean cleared) {
      List<ContentBlockParam> blocks = new ArrayList<>();
      for (ToolCall c : calls) blocks.add(c.toBlock(cleared));
      if (note != null) blocks.add(ContentBlockParam.ofText(note));
      return MessageParam.ofUserOfContentBlockParams(blocks);
    }
  }

  record ToolCall(String id, String name, String args, String result, boolean isError) {
    ContentBlockParam toBlock(boolean cleared) {
      var b =
          ToolResultBlockParam.builder()
              .toolUseId(id)
              .content(
                  cleared ? "[cleared: %s(%s). Re-run if needed.]".formatted(name, args) : result);
      if (isError) b.isError(true); // only when true:
      return ContentBlockParam.ofToolResult(b.build());
    }
  }

  static final class KeepAll implements ContextStrategy {
    public List<MessageParam> build(MessageParam firstUser, List<Step> history, int currentTurn) {
      List<MessageParam> out = new ArrayList<>();
      out.add(firstUser);
      for (Step s : history) {
        out.add(s.assistant()); // unchanged
        out.add(s.toolResultsMsg(false)); // unchanged
      }
      return out;
    }
  }

  static final class ClearOld implements ContextStrategy {
    private final int keepLast;

    ClearOld(int keepLast) {
      this.keepLast = keepLast;
    }

    @Override
    public List<MessageParam> build(MessageParam firstUser, List<Step> history, int currentTurn) {
      final List<MessageParam> out = new ArrayList<>();
      out.add(firstUser);

      int clearedCalls = 0;
      long clearedChars = 0;
      for (Step s : history) {
        final boolean clearOpt = (currentTurn - s.turn > keepLast);
        out.add(s.assistant);
        out.add(s.toolResultsMsg(clearOpt));

        // telemetry
        if (clearOpt) {
          clearedCalls += s.calls().size();
          for (ToolCall c : s.calls()) clearedChars += c.result().length();
        }
      }

      log.atDebug().log(
          "turn {} cleared {} results, ~{} chars", currentTurn, clearedCalls, clearedChars);
      return out;
    }
  }
}
