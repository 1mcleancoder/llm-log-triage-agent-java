package i.m.clean.coder.triage;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.models.messages.*;
import com.fasterxml.jackson.core.JsonProcessingException;
import java.nio.file.Path;
import java.util.*;
import lombok.extern.slf4j.Slf4j;

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
      log.info("=".repeat(80));
      log.info("Run # {}: ", i + 1);
      log.info("=".repeat(80));
      final RunResult result = runOnce(client, logFiles, model, maxTokens, maxInputTokens, costUsd);
      log.info("=".repeat(80));
      log.info("Output: ");
      log.info("=".repeat(80));
      log.info("\n{}\n", result.toPrettyString());
      Telemetry.printTelemetry(result.getTelemetries());
      results.add(result);
    }

    Set<String> logLines = logFiles.getAllLogLines();
    RunResult.printResults(results, logLines);

    client.close();
  }

  private static RunResult runOnce(
      AnthropicClient client,
      LogFiles logFiles,
      Model model,
      long maxTokens,
      long maxInputTokens,
      double maxCost) {
    StructuredMessage<IncidentSummary> message;
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
            .system(Stage4AccuracyHelper.constructSystemMessage())
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
            false,
            EndedBy.max_tokens,
            null,
            i + 1,
            totalInTokens,
            totalCost,
            latencyMs,
            telemetries);
      }

      if (stopReason.equals(StopReason.TOOL_USE.asString())) {
        conversation.addMessage(message.rawMessage());
        List<ContentBlockParam> userBlocks = new ArrayList<>(processToolBlocks(logFiles, message));

        boolean outOfTurns = (i + 1) >= MAX_TURNS;
        boolean overBudget = totalCost >= maxCost || totalInTokens >= maxInputTokens;
        if (outOfTurns || overBudget) { // the model wants more; we say "answer now"
          budgetReached = true;
          endReason = outOfTurns ? EndedBy.turn_limit : EndedBy.budget;
          userBlocks.add(ContentBlockParam.ofText("Budget reached. Give your final answer now."));
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
        EndedBy.error,
        null,
        MAX_TURNS + 1,
        totalInTokens,
        totalCost,
        latencyMs,
        telemetries);
  }

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
                resultBuilder.content(ToolUtils.runTool(toolUse, logFiles));
              } catch (Exception e) {
                resultBuilder.content("Error: " + e.getMessage()).isError(true);
              }

              return ContentBlockParam.ofToolResult(resultBuilder.build());
            })
        .toList();
  }

  enum EndedBy {
    end_turn,
    budget,
    max_tokens,
    time_overrun,
    turn_limit,
    error
  }
}
