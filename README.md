# LLM Log Triage Agent (Java)

An agent that investigates a production incident across four services — ~44,000 log lines, ~6 MB — and reports a root cause with the evidence it used. Built with the Anthropic Java SDK, with token and cost telemetry on every model call.

> Scaffold and synthetic incident data were generated with Claude. Stage 2 onward — the agent loop, tool layer, structured output, telemetry and all experiments — is my own work.

## The demo

Ask one question:

> Payments started failing around 14:00. What happened?

The agent decides what to look at. Given three log tools and no hints, it searched `gateway.log`, found the connection pool saturated at `active=20/20`, and tied it to the payments errors through a single shared trace id — correlating two services without being told they were related.

It returns a validated structure, not prose:

```json
{
  "severity": "HIGH",
  "first_seen": "ISO timestamp or null",
  "affected_services": ["..."],
  "root_cause_guess": "string or \"unknown\"",
  "evidence": ["file:line or quoted log line"],
  "suggested_action": "string"
}
```

Two of the four runbook pages in the dataset are decoys, and two error codes in the logs are false leads. Every claim in `evidence` is checked back against the source logs, so a hallucinated line fails verification rather than reaching the report.

## What I measured

The point of building this in stages was to instrument each one rather than trust it. Findings so far:

| Finding                                                | Detail                                                                                                                                                  |
|--------------------------------------------------------|---------------------------------------------------------------------------------------------------------------------------------------------------------|
| Hidden thinking tokens can eat the whole output budget | Produces an empty answer with `stop_reason=max_tokens` — looks like a model failure, is actually a budget accounting problem                            |
| Cost per investigation                                 | ~$0.001 for a single Haiku 5.5 call; ~$0.008 for a five-turn tool-using investigation                                                                   |
| Tokenizer changes are not free                         | The same text costs ~30% more tokens on Haiku 5.5 than on 4.5                                                                                           |
| Prompt overhead is measurable                          | Two few-shot examples ≈ 500 tokens; the output schema ≈ 500 tokens                                                                                      |
| Agent cost is driven by accumulated tool results       | Input tokens per turn across one investigation: 2,133 → 6,406 → 17,366 → 18,268 → 20,353. The API is stateless, so every turn resends the whole history |
| Few-shot examples behave differently by source         | Examples drawn from the *same* incident get copied verbatim into the answer; examples from a *different* incident set conventions instead               |
| `temperature` is gone from the API                     | Rejected with "Extra inputs are not permitted", including on older models                                                                               |

## How it works

```
src/main/java/i/m/clean/coder/triage/
  LogFiles / LogUtils / LogFilesSmokeTest   Log access: list, regex search, line ranges, time windows.
                                            Evidence verification. Runs with no API key.
  ToolUtils                                 Tool schemas: list_files, search_logs, read_lines
  IncidentSummary / IncidentSeverity        The answer as a Java record; field descriptions
    / Nullable                              generate the JSON schema
  ModelPricing / TriageUtils                Per-call cost from a price table; telemetry output
  Stage1* / Stage2* / Stage3*               Each stage runnable on its own, for comparison

data/logs/        gateway, payments, orders, auth — 12:00–16:00
data/runbooks/    Four pages; one holds the real fix
answers/          Ground truth, held back for the eval stage
```

Design decisions worth noting:

- **Tool errors are results, not exceptions.** Returned with `is_error` so the model can correct its own regex instead of the run dying.
- **Truncation is announced.** Tool results are capped, and the result tells the model it was cut, so it can ask for more rather than reasoning from a silent partial.
- **The loop was written by hand** before reading the SDK's tool runner, to understand `tool_use` / `tool_result` id matching rather than inherit it.
- **Structured output three ways** — prompt-only, few-shot, and schema-enforced — kept side by side so the trade-offs are measurable rather than assumed.

## Instrumentation

Every model call reports input and output tokens, `stop_reason`, latency and computed cost; tool-using runs report this per turn. Evidence lines are verified against the logs on every answer.

In progress: OpenTelemetry spans per model call and per tool call, using the GenAI semantic conventions, so an investigation reads as a trace rather than a console dump — plus a scorer that runs the agent repeatedly against known ground truth and reports a distribution instead of one impression. Both come before the agent gets more complex, because every later change is one whose effect needs measuring.

## Run it

Requires **JDK 17 exactly** (not 18+) and Maven 3.9+. The build enforces this.

```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 17)
export ANTHROPIC_API_KEY=sk-ant-...        # never in code
```

Check the log helpers — no API key needed:

```bash
mvn -q compile exec:java -Dstage=i.m.clean.coder.triage.LogFilesSmokeTest
```

Run the first stage:

```bash
mvn -q compile exec:java
```

Haiku is the default; switch models only to compare quality. If Maven can't resolve the SDK, update `anthropic.version` in `pom.xml` to the current release.

Formatting is enforced by Google Java Format via `fmt-maven-plugin` — `mvn verify` fails on unformatted files, `mvn fmt:format` fixes them.

## Build log

Built in stages, each one instrumented and then deliberately broken to find its failure modes. Stages 1–3 are complete: the first call and token accounting, structured output three ways, and the tool-use loop. In progress: traces and a repeat-run scorer, before the agent gets more complex.

- **[NOTES.md](NOTES.md)** — the experiment log. Every finding as expected / observed / why / lesson, including the measurements that turned out to be wrong and what the mistake was.
- **[STAGES.md](STAGES.md)** — the build plan: what each stage adds, and how each one gets broken on purpose.

## License

MIT
