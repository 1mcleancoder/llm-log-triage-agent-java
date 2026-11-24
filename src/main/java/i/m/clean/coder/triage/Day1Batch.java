package i.m.clean.coder.triage;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.errors.BadRequestException;
import com.anthropic.models.messages.Model;
import com.anthropic.models.messages.TextBlock;
import com.anthropic.models.messages.batches.BatchCreateParams;
import com.anthropic.models.messages.batches.MessageBatch;
import com.anthropic.models.messages.batches.MessageBatchResult;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;

/**
 * The task: Day1Batch.java
 *
 * <p>Use a batch to classify the severity of 5 log lines. Pick them from your own data, for example
 * the campaign line, an ERR-4012, the ERR-4013 TLS warning, an ERR-2201 auth warning and a normal
 * INFO line.
 */
@Slf4j
public class Day1Batch {
  public static void main(String[] args) throws InterruptedException {

    Map<String, String> lineById = getLineById();

    var batchParamsBuilder = buildBatchRequests(lineById);

    AnthropicClient client = null;
    try {
      client = AnthropicOkHttpClient.fromEnv();
      MessageBatch batch = client.messages().batches().create(batchParamsBuilder.build());
      log.info("ACCEPTED batch {} counts={}", batch.id(), lineById.size());

      batch = waitForBatchProcessingToFinish(batch, client);
      log.info("CHECKING the batch results");

      checkBatchProcessingResults(client, batch, lineById);
    } catch (BadRequestException exception) {
      log.error("REJECTED at create (case A): {}", exception.getMessage());
    } finally {
      if (client != null) {
        client.close();
      }
    }
  }

  private static void checkBatchProcessingResults(
      AnthropicClient client, MessageBatch batch, Map<String, String> lineById) {
    List<String> arrivalOrder = new ArrayList<>();
    try (var stream = client.messages().batches().resultsStreaming(batch.id())) {
      stream.stream()
          .forEach(
              r -> {
                String id = r.customId();
                arrivalOrder.add(id);

                MessageBatchResult result = r.result();
                if (result.isSucceeded()) {
                  var msg = result.asSucceeded().message();
                  String label =
                      msg.content().stream()
                          .flatMap(c -> c.text().stream())
                          .map(TextBlock::text)
                          .findFirst()
                          .orElse("?");
                  log.info(
                      "{} -> {} | in={} | out={} | {}",
                      id,
                      label,
                      msg.usage().inputTokens(),
                      msg.usage().outputTokens(),
                      lineById.get(id));
                } else if (result.isErrored()) {
                  log.warn("{} ERRORED: {}", id, result.asErrored().error());
                } else if (result.isCanceled()) {
                  log.warn("{} CANCELED", id);
                } else if (result.isExpired()) {
                  log.warn("{} EXPIRED", id);
                }
              });
    }
    log.info("arrival order: {}", arrivalOrder);
  }

  @NotNull
  private static MessageBatch waitForBatchProcessingToFinish(
      MessageBatch batch, AnthropicClient client) throws InterruptedException {
    log.info(
        "sent batch of {} requests, now checking if batch processing status in a loop",
        batch.requestCounts());

    int waitMs = 10_000;
    while (!MessageBatch.ProcessingStatus.ENDED.equals(batch.processingStatus())) {
      log.info(
          "status={} counts={} sleeping {}s ... ",
          batch.processingStatus(),
          batch.requestCounts(),
          waitMs / 1000);
      Thread.sleep(waitMs);
      waitMs = Math.min(waitMs * 2, 120_000); // exponential backoff
      batch = client.messages().batches().retrieve(batch.id());
    }

    log.info("PROCESSING ENDED created={}, ended={}", batch.createdAt(), batch.endedAt());
    return batch;
  }

  @NotNull
  private static BatchCreateParams.Builder buildBatchRequests(Map<String, String> lineById) {
    var batchParamsBuilder = BatchCreateParams.builder();
    log.info(
        "creating {} batch requests, where each line is sent in a single batch request to quantify",
        lineById.size());
    for (Map.Entry<String, String> entry : lineById.entrySet()) {
      batchParamsBuilder.addRequest(
          BatchCreateParams.Request.builder()
              .customId(entry.getKey())
              .params(
                  BatchCreateParams.Request.Params.builder()
                      .model(
                          entry.getKey().equals("line-6")
                              ? Model.of("claude-does-not-exist")
                              : Model.CLAUDE_HAIKU_5_5)
                      .maxTokens(200)
                      .addUserMessage(
                          "Classify severity (HIGH/MEDIUM/LOW) of this log line, one word: "
                              + entry.getValue())
                      .build())
              .build());
    }
    return batchParamsBuilder;
  }

  @NotNull
  private static Map<String, String> getLineById() {
    Map<String, String> lineById = new LinkedHashMap<>();
    lineById.put(
        "line-1",
        "2026-10-25T13:54:20.000Z INFO  [orders] [scheduler] trace=- campaign FLASH-OCT25 activated: 30% off sitewide, push notification sent to 1.2M users");
    lineById.put(
        "line-2",
        "2026-10-25T14:02:16.542Z ERROR [payments] [pay-exec-27] trace=0ae8d8e65ea9df94 ERR-4012 Payment gateway timeout order_id=ord-1125289 upstream=gateway/card-acquirer elapsed_ms=5051 retry=1/2");
    lineById.put("line-3", "<ERR-4013 line from grep>");
    lineById.put("line-4", "<ERR-2201 line from grep>");
    lineById.put(
        "line-5",
        "2026-10-25T14:02:11.497Z INFO  [payments] [pay-exec-27] trace=0ae8d8e65ea9df94 charge requested order_id=ord-1125289 amount_usd=42.34 method=card");
    lineById.put("line-6", lineById.get("line-2")); // same text, bad model
    return lineById;
  }
}
