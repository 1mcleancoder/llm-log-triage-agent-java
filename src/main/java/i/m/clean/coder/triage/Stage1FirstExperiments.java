package i.m.clean.coder.triage;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.errors.BadRequestException;
import com.anthropic.models.messages.*;
import lombok.extern.slf4j.Slf4j;

import java.nio.file.Path;
import java.util.List;

@Slf4j
public class Stage1FirstExperiments {
    private Stage1FirstExperiments() {}

    public static void main(String[] args) {
        final Model model = args.length > 0 ? Model.of(args[0]) : Model.CLAUDE_HAIKU_5_5;
        log.info("Using model: " + model);
        final long maxTokens = args.length > 1 ? Long.parseLong(args[1]) : 512;

        // 1. Cut a small portion of logs aroung the same time when the problem occurred
        final LogFiles logs = new LogFiles(Path.of("data/logs"));
        final List<String> excerpt =
                logs.between("payments.log",
                        "2026-10-25T14:02:00",
                        "2026-10-25T14:03:00", 300);

        // 2. wrap the data in xml so that model can tell the data from instructions
        final String userPrompt = "<logs file=\"payments.log\">\n"
                + String.join("\n", excerpt) + "\n</logs>\n\n"
                + "Payments started failing around 14:00. What is going wrong? "
                + "Answer in 3 to 5 sentences and quote the logs lines you rely on.";
        // 3. build the message and send it across
        final AnthropicClient client = AnthropicOkHttpClient.fromEnv();
        final MessageCreateParams params =
                MessageCreateParams.builder()
                        .model(model)
                        .maxTokens(maxTokens)
                        .thinking(ThinkingConfigParam.ofDisabled(ThinkingConfigDisabled.builder().build()))
                        .system("You are an experienced side reliability engineer triaging a production incident. " +
                                "Be concise and base every claim on the logs provided.")
                        .addUserMessage(userPrompt)
                        .build();

        long start = System.nanoTime();
        try {
            Message message = client.messages().create(params);
            long latencyMs = (System.nanoTime() - start) / 1_000_000;

            // 4. read the results
            int thinkingTokens = 0;
            int ansChars = 0;

            for (var block : message.content()) {
                if (block.isThinking()) {
                    thinkingTokens += block.thinking().get().thinking().length();
                    log.info("thinking ... " + block.thinking().get().thinking());
                } else if (block.isText()) {
                    TextBlock txt = block.text().get();
                    ansChars += txt.text().length();
                    log.info("[text block]: " + txt.text());
                } else {
                    log.info("[other type]: " + block.type());
                }
            }

            Usage usage = message.usage();
            log.info("---- telemetry ----");
            log.info("model               = {}", message.model());
            log.info("stop_reason         = {}", message.stopReason());
            log.info("input_tokens        = {}", usage.inputTokens());
            log.info("output_tokens       = {}", usage.outputTokens());
            log.info("thinking_tokens     = {}", thinkingTokens);
            log.info("answer tokens       = {}", ansChars);
            log.info("estimated_thinking tokens = {}", usage.outputTokens() - (ansChars / 4));
            log.info("latency_ms          = {}", latencyMs);
            log.info("excerpt_lines       = {}", excerpt.size());
        } catch (BadRequestException e) {
            log.info("400 error from API: " + e.getMessage());
        }
        System.exit(0);
    }
}
