# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build & Run Commands

Requires JDK 17 and Maven (via sdkman). Set `ANTHROPIC_API_KEY` in the environment.

```bash
# Compile and run default stage (Stage1FirstCall)
mvn -q compile exec:java

# Run a specific stage
mvn -q compile exec:java -Dstage=i.m.clean.coder.triage.Stage3ToolUse

# Smoke test log file access (no API key needed)
mvn -q compile exec:java -Dstage=i.m.clean.coder.triage.LogFilesSmokeTest

# Auto-format code (Google Java Format — enforced on build)
mvn fmt:format

# Full build with format check
mvn verify
```

Available stages: 
- Each stage is a runnable class: src/main/java/i/m/clean/coder/triage/Stage*.java
  Run with -Dstage=i.m.clean.coder.triage.<ClassName>

## Architecture

This is a production incident triage agent built with the Anthropic Java SDK. The agent investigates incidents across four simulated services by autonomously searching ~44,000 log lines and returning a structured root cause analysis.


## Where things live (non-obvious)
- RunResult: eval scoring (mechanism / trigger / grounded) and the summary line
- ContextStrategy: KeepAll / ClearOld, which decide what history is sent each turn
- ModelPricing: cost per usage; no batch discount yet

## Ground truth (NEVER edit)
- data/logs/ and answers/ are eval ground truth. Change the agent, not the data.
  (Enforced by .claude/hooks/protect-ground-truth.sh)
- `data/logs/` — `{payments,gateway,orders,auth}.log` (~12–17K lines each)
- `data/runbooks/` — Markdown runbooks referenced during triage

**Staged build plan:** See `STAGES.md` for the 10-stage progression (each stage adds one capability). `NOTES.md` contains detailed experiment findings from each stage.

## Conventions
- Records for data types 
- Lombok @Slf4j for logging 
- JSON fields are snake_case via @JsonProperty

## Experiment rules
- Change one thing at a time; record every eval in NOTES.md

## Important API Behaviours

- **Temperature** is no longer accepted by the Anthropic API — it returns a 400. Do not add it.
- **Thinking tokens** are hidden by default but still count against the output token budget. Monitor via `usage.outputTokensDetails().thinkingTokens()`.
- **Tool result truncation:** Telling the model when results are capped changes its search strategy (broad → narrow per-file).
- **Context growth:** Input tokens grow ~10× over a multi-turn investigation. Stage 5A clears results older than 3 turns, and only those over 1,000 chars
- **Few-shot examples:** Use a *different* incident than the one being investigated — same-incident examples cause the model to copy answers rather than reason.