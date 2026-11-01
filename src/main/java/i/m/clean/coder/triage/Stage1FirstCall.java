package i.m.clean.coder.triage;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.models.messages.*;
import lombok.extern.slf4j.Slf4j;

import java.nio.file.Path;
import java.util.List;

/**
 * Stage 1: one log excerpt, one question, one answer.
 *
 * Run:  mvn -q compile exec:java
 * Optional args (via -Dexec.args="..."): model id, max tokens.
 *   mvn -q compile exec:java -Dexec.args="claude-sonnet-5-5 40"
 *
 * Needs ANTHROPIC_API_KEY in the environment. Never put the key in code.
 */
@Slf4j
public final class Stage1FirstCall {

    private Stage1FirstCall() {}

    public static void main(String[] args) throws Exception {
        Model model = args.length > 0 ? Model.of(args[0]) : Model.CLAUDE_HAIKU_5_5;
        long maxTokens = args.length > 1 ? Long.parseLong(args[1]) : 1024;

        // 1. Cut a small excerpt around the time the problem was reported.
        LogFiles logs = new LogFiles(Path.of("data/logs"));
        List<String> excerpt =
                logs.between("payments.log", "2026-10-25T14:02:00", "2026-10-25T14:03:00", 80);

        // 2. Wrap the data in XML tags so the model can tell data from instructions.
        String userPrompt = "<logs file=\"payments.log\">\n"
                + String.join("\n", excerpt)
                + "\n</logs>\n\n"
                + "Payments started failing around 14:00. What is going wrong? "
                + "Answer in 3 to 5 sentences and quote the log lines you rely on.";

        // 3. Build and send the request.
        MessageCreateParams params = MessageCreateParams.builder()
                .model(model)
                .thinking(ThinkingConfigParam.ofDisabled(ThinkingConfigDisabled.builder().build()))
                .maxTokens(maxTokens)
                .system("You are an experienced site reliability engineer triaging a production incident. "
                        + "Be concise and base every claim on the logs provided.")
                .addUserMessage(userPrompt)
                .build();

        AnthropicClient client = AnthropicOkHttpClient.fromEnv();
        try {
            long start = System.nanoTime();
            Message message = client.messages().create(params);
            long latencyMs = (System.nanoTime() - start) / 1_000_000;

            // 4. Print the answer, then the numbers you will later turn into telemetry.
            int thinkingChars = 0;
            int answerChars = 0;
            for (var block : message.content()) {
                if (block.isThinking()) {
                    log.info("[thinking block: " + block.thinking().get().thinking().length() + " chars -> thinking text: " + block.thinking().get().thinking());
                    thinkingChars += block.thinking().get().thinking().length();
                } else if (block.isText()) {
                    log.info("[text block]: " + block.text().get().text());
                    answerChars += block.text().get().text().length();
                } else {
                    log.info("[other block type]" + block.type());
                }
            }

            Usage usage = message.usage();

            log.info("--- telemetry ---");
            log.info("model          = {}", message.model());
            log.info("stop_reason    = {}", message.stopReason().map(Object::toString).orElse("none"));
            log.info("input_tokens   = {}", usage.inputTokens());
            log.info("output_tokens  = {}", usage.outputTokens());
            log.info("thinking_chars = {}", thinkingChars);
            log.info("answer_chars   = {}", answerChars);
            log.info("estimaged_thinking_tokens = {}", usage.outputTokens() - (answerChars / 4));
            log.info("latency_ms     = {}", latencyMs);
            log.info("excerpt_lines  = {}", excerpt.size());
        } finally {
            if (client instanceof AutoCloseable c) c.close();
        }
        System.exit(0);
    }
}
