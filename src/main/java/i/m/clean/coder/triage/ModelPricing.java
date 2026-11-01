package i.m.clean.coder.triage;

import com.anthropic.models.messages.Usage;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.util.Comparator;
import java.util.Map;
import java.util.OptionalDouble;

@Slf4j
public final class ModelPricing {

    public record Rates(double input, double output,
                        @JsonProperty("cache_write_5m") double cacheWrite5m,
                        @JsonProperty("cache_read") double cacheRead) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Price(double input, double output,
                        @JsonProperty("cache_write_5m") double cacheWrite5m,
                        @JsonProperty("cache_read") double cacheRead,
                        @JsonProperty("long_context_threshold") Long longContextThreshold,
                        @JsonProperty("long_context") Rates longContext) {
    }

    private final Map<String, Price> prices;

    public ModelPricing() throws IOException {
        try (var in = ModelPricing.class.getResourceAsStream("/model-prices.json")) {
            Map<String, Object> raw = new ObjectMapper().readValue(in, new TypeReference<>() {
            });
            raw.remove("_source");
            this.prices = new ObjectMapper().convertValue(raw, new TypeReference<>() {
            });
        }
    }

    /**
     * Cost in USD, or empty if the model isn't in the table. Never guesses.
     */
    public OptionalDouble cost(String modelId, Usage u) {
        Price p = lookup(modelId);
        if (p == null) return OptionalDouble.empty();

        long cacheRead = u.cacheReadInputTokens().orElse(0L);
        long cacheWrite = u.cacheCreationInputTokens().orElse(0L);
        long promptSize = u.inputTokens() + cacheRead + cacheWrite;

        boolean longCtx = p.longContextThreshold() != null && promptSize > p.longContextThreshold();
        double pIn = longCtx ? p.longContext().input() : p.input();
        double pOut = longCtx ? p.longContext().output() : p.output();
        double pCw = longCtx ? p.longContext().cacheWrite5m() : p.cacheWrite5m();
        double pCr = longCtx ? p.longContext().cacheRead() : p.cacheRead();

        final double cost = (u.inputTokens() * pIn + u.outputTokens() * pOut
                + cacheWrite * pCw + cacheRead * pCr) / 1_000_000.0;
        log.info("cost={}, cacheRead={}, cacheWrite={}, promptSize={}, longCtx={}, pIn={}, pOut={}, pCw={}, pCr={}", cost, cacheRead, cacheWrite, promptSize, longCtx, pIn, pOut, pCw, pCr);
        return OptionalDouble.of(cost);
    }

    /**
     * The response says "claude-haiku-4-5-20251001"; the table says "claude-haiku-4-5".
     */
    private Price lookup(String modelId) {
        return prices.entrySet().stream()
                .filter(e -> modelId.startsWith(e.getKey()))
                .max(Comparator.comparingInt(e -> e.getKey().length()))
                .map(Map.Entry::getValue)
                .orElse(null);
    }
}