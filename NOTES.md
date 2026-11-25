What I learned:
1. About: Temperature support
   What you expected: temperature field should be there
   what happened: temperature is no longer supported; after adding the property it gives 400 error “temperature: Extra inputs are not permitted”
   Why: The API removed the field from its request schema, so it’s rejected before any model sees it, even at 1.0. The SDK simply follows the API. 
   Lesson: API follows its request schema which does not support temperature anymore. Also, I learnt that we can add any additional parameters by using putAdditionalBodyProperty. But it can result in rejection. 

2. About: why Max tokens
   What you expected: input tokens + output tokens
   what happened: max tokens limit output only (thinking + answer). The input is limited by the context window.
   Why: it's a guarding condition to check that output tokens should be < than max tokens. 
   Lesson: Always check stop_reason before trusting the answer.

3. About: Thinking tokens on/off
   What you expected: should be off by default
   what happened: Haiku 5.5 was on
   Why: Haiku 5.5. has thinking on by default; I disabled it with ThinkingConfigDisabled for simple extraction.
   Lesson: if you do not want thinking tokens, then make sure to turn it off

4. About: Thinking output
   What you expected: something should be there in output if it is thinking
   what happened: thinking tokens is not present in the output
   Why: Haiku 5.5 defaults to thinking display omitted, so the block comes back with empty text. But you’re still billed for the thinking, and the exact count is in usage.outputTokensDetails().thinkingTokens().
   Lesson: Thinking output can be absent from output even though thinking is on, and it is consuming tokens!!

5. About: stop_reason as ‘max_tokens’
   What you expected: tokens are consumed by actual input + output
   what happened: actual tokens are consumed by thinking tokens
   Lesson: by default thinking tokens are enabled and thinking output is disabled so there was nothing in the thinking tokens output. So the reason for the stop reason as max_tokens is thinking tokens which I verified by calculating the estimated cost.

6. About: Haiku 5.5 and 4.5 cost comparison
   What you expected: 5.5 cost high
   what happened: Haiku 5.5 is much cheaper than Haiku 4.5
   Why: Anthropic hasn’t said why but my guess is because of competition.
   Lesson: 5.5 is up to 90% cheaper, but it consumes 30% more tokens. Overall its cost is much lower. 

7. About: System prompt output suggestion without prompts
   What you expected: Output should produce exact text
   what happened: It does not follow the instructions and make errors
   Why: prompt instructions make a format likely, not guaranteed, so validate in code or use structured outputs.
   Lesson: model can produce extra formatting telling the output format even though we tell it not to produce it like in our case it produced ```json which is not required and the verbatim text is also not followed.

8. About: Few-shot examples
   What you expected: one example would just teach the JSON format 
   What happened: with an example from the same incident, the model copied its answer (same first_seen, same wording, same wrong root cause); with examples from
   different incidents it analyzed the logs itself
   Why: models copy examples very closely. 
   Lesson: Examples should show the shape using a different incident. They also set conventions silently (first_seen = first ERROR line). 

9. About: Evidence validation
   What you expected: if every evidence line exists in the logs, the evidence is good
   What happened: a "health check ok" line passed the check but supported nothing
   Why: a verbatim check proves the evidence is grounded (not invented), not that it's relevant. Relevance needs a human review or an LLM judge (stage 9).

10. About: Structured Output input tokens
   What you expected: Input tokens would stay the same
   What happened: They increase by 400 tokens
   Why: Structured output is sent as a schema which is much more verbose which increase the token size

11. About: Removing examples with Structured output
    What you expected: output quality would be almost same
    What happened: output quality is affected such that the root cause guess contains much more text
    Why: schema enforces shape and examples and descriptions guide content

12. About: example cost!!
    What you expected: example would increase cost
    What happened: examples decrease overall cost
    Why: example can result in more concise output and since output tokens cost 5x more in Haiku so concise output means less costly.

13. About: Tools use 
    What you expected: I expected that Claude will invoke the tools it needs
    What happened: It just tells you what tool you need to invoke and with what parameter
    Why: Claude just works with tool descriptions and inputs with examples, it tells you to invoke the tool with Claude provided inputs

14. About: Tools use with Structured output
    What you expected: Tools will not work with Structured output
    What happened: It supports structured output even with tool use
    Why: Structured output is the most common way to interact with tool use so the support is present

15. About: Tools use requires more tokens
    What you expected: It will not increase tokens 
    What happened: Tokens are increased a lot and the output is much more accurate
    Why: Since each message is independent, so we need to pass the old message with tool details, everytime and this increases the tokens. But the eventual result is much more accurate.

16. About: Parallel tool calls (multiple tool_use blocks in one message)
    What you expected: one tool call per turn; I was not sure parallel calls would work
    What happened: the model requested two searches in one response (payments + gateway); the loop ran both and returned both results in one user message
    Why: the calls were independent, and every tool_result carries its tool_use_id so the model can match each result to its request
    Lesson: parallel calls save whole turns (and each turn resends the full history). All results must go back in ONE user message. Running them concurrently with .parallel() is fine for read-only tools only.

17. About: Token growth across agent turns
    What you expected: each turn would cost about the same
    What happened: input tokens grew every turn: 2,133 -> 6,406 -> 17,366 -> 18,268 -> 20,353 (total 64,526 over 5 turns, ~$0.0078)
    Why: the API is stateless, so every turn resends the whole history, including every tool result returned so far
    Lesson: in an agent, cost and latency are driven by accumulated tool results. Log tokens per turn AND cumulative; this is the problem context engineering (stage 5) solves.

18. About: Telling the model when tool results are truncated
    What you expected: capping search results at 50 lines was enough
    What happened: without a truncation notice the model could not tell "exactly 50" from "50 of 2,000"; a broad first search across all files likely returned only auth.log noise (ERR-2201) and wasted a turn. After adding "[truncated: narrow the regex or pick a file]" it narrowed its searches per file and used maxResults=3 for confirmation.
    Why: the model only knows what the tool result tells it
    Lesson: a tool result should say what it did NOT return. Small feedback in the result changes the model's next move.

19. About: Tools found the real root cause
    What you expected: the answer would be similar to stage 2
    What happened: stage 2 (payments.log only) blamed the upstream provider; with tools the agent searched gateway.log, found pool=card-acquirer active=20/20 with waiting requests, and linked it to payments ERR-4012 via the same trace id
    Why: with an excerpt the model can only reason about what you send; with tools it can go and look
    Lesson: a wrong answer was a context problem, not a reasoning problem. Following one trace id across services is the key investigation step.

20. About: Run-to-run variation
    What you expected: the same prompt would give the same answer
    What happened: root cause was consistent, but first_seen differed on every run (13:55, 14:00:02, 13:57) and evidence selection varied
    Why: the pool fills gradually, so each run samples a different "first" line; sampling and different search paths add variation
    Lesson: never judge an agent on one run. Evals (stage 9) should run several times, score the root cause strictly and accept a time range for first_seen.

21. About: Structured output guarantees shape, not content
    What you expected: valid JSON means clean output
    What happened: suggested_action ended with a stray ".}" inside the string; the JSON was still valid
    Why: the schema constrains structure (keys, types, enums), not the text inside string fields
    Lesson: keep content checks (evidence verbatim, no stray characters, length limits) even with structured output.

22. About: The model copies the tool's output format
    What you expected: evidence would always be plain log lines
    What happened: sometimes evidence came back as "gateway.log:1980: <line>" because search_logs returns lines in that format
    Why: the model copies whatever format it saw in the tool result
    Lesson: normalise in code (strip the file:line prefix) before validating; don't rely on the prompt alone.

23. About: Correction - examples did not cost +32% tokens
    What you expected: (earlier I recorded) two few-shot examples added ~32% input tokens
    What happened: that comparison mixed Haiku 4.5 (prompt-only) with Haiku 5.5 (few-shot); on the same model the examples cost ~500 tokens (~5%) and the schema ~500
    Why: Haiku 5.5's tokenizer counts the same text as ~30% more tokens than 4.5
    Lesson: change one thing at a time. Compare models by cost per task, not price per token.

24. About: The agent stops at the symptom
    What you expected: it would explain why the pool filled up
    What happened: it found the saturation but said "the logs do not show why card-acquirer latency increased"; it never searched orders.log or looked at what changed before 13:55
    Why: nothing in the prompt asked it to look for a trigger, and it stopped once it had a plausible answer
    Lesson: stage 4 goal - prompt it to look for the trigger (traffic, deploys, config) in the minutes before onset.

## Stage 4 — Accuracy & evals

### 25. Validate the eval harness before trusting the score
First 5-run eval said "trigger found 1/5", but the harness was broken:
- cost was the last turn only (reported ~$0.005/run, real ~$0.025),
  so the cost budget could never fire
- turns were zero-based
- a turn-limit path returned null

**Lesson:** a broken measurement is worse than none because it looks precise.
Check harness numbers against raw telemetry by hand before reading the score.

### 26. Score at several levels, not one keyword
The keyword scorer ("campaign" in root_cause_guess) was too strict AND too lenient:
- too strict: runs that correctly found "4x traffic → pool 20/20 → timeouts"
  scored as failures
- too lenient: "campaign" came from the 14:40 post-incident on-call note,
  not from finding the trigger

Now scored as:
- mechanism: load increase + pool saturation in the guess
- trigger: the 13:54:20 campaign line is in evidence
- grounded: all evidence lines are verbatim
- PASS = trigger AND grounded

**Lesson:** one number hides *where* the agent fails.
Validate the scorer by reading transcripts.

### 27. Read transcripts before changing the prompt — the tool hid the evidence
0/5 runs found FLASH-OCT25 even though the model searched for "flash"/"campaign":
- the regex was case-sensitive ("flash" ≠ "FLASH-OCT25")
- broad cross-file searches were flooded by `max=20` health lines and
  truncated before reaching orders.log

The fix was in the tool, not the prompt: the truncation note now reports
per-file match counts.
Result: trigger found 0/5 → 7/10 across the next two batches (Fisher p ≈ 0.03).

**Lesson:** tool design (what results look like, how truncation reads)
matters as much as the prompt.

### 28. Small samples lie
0/5 → 3/5 looked like a win, but p ≈ 0.17 (not significant).
Only after 7/10 was the effect credible.

**Lesson:** run enough trials before claiming an improvement.
At ~$0.025/run, 15–20 runs are cheap.

### 29. The model fabricated an evidence line
A run cited:
`14:00:01.000Z … trace=6be5f62ec8eced15 … latency_ms=1840`

Only the 14:02:00.729Z line exists (gateway.log:2380). The model copied a real
line and changed the timestamp to support a "saturated from 14:00" claim.
Tell-tale sign: round `.000` milliseconds.

Neither the prompt ("evidence must be verbatim") nor structured outputs
prevented it. **The schema guarantees shape, not truth.**

Fix: verbatim check against all log lines in code; grounding gates PASS.

Known gap: metrics are not verified (one run claimed "~1605 timeout lines"
with no matching count).

### 30. Agent loop control rules
- Only `tool_use` continues the loop, so the turn-limit/budget check
  belongs in that branch.
- On limit: add a text block AFTER the tool_result blocks in ONE user message,
  and set ToolChoiceNone to force a final answer.
- Every tool_use still needs its tool_result.
- The API merges consecutive same-role turns, but build one message anyway:
  guaranteed ordering, logs match what the model saw, no reliance on
  server behaviour.
- Unexpected stop reasons (refusal, pause_turn) → end with EndedBy.error,
  never a silent retry.
- Separate end reasons: end_turn / budget / turn_limit / max_tokens / error.
  max_tokens is output truncation, not a spend budget.

### 31. The budget is soft — describe it honestly
The budget is checked between turns, so the final turn can overshoot
(a run hit 287K vs a 250K cap, $0.032 vs $0.03).

Say "no new turns start once input passes X", not "input never exceeds X".

Hard-cap options:
- headroom: stop at cap − typical turn size
- prediction: estimate the next turn's input before calling

### 32. Telemetry: per-turn vs cumulative
The TOTAL row summed cumulative columns (cost showed $0.096 for a $0.025 run,
latency 123 s for a 27 s run).

Store each metric as Counts(value, total).
TOTAL = last cumulative value, never a sum of totals.

### 33. Few-shot examples leak
An incident-specific metric (`orders/5min: 13:50 ≈ 250 → 13:55 ≈ 900`) left in
the auth example was copied nearly verbatim in format by a run.

**Lesson:** examples must not contain anything from the real incident.

### 34. Stage 4 baseline (Haiku 5.5, 5 runs)
| Metric                      | Value   |
|-----------------------------|---------|
| PASS                        | 4/5     |
| mechanism                   | 5/5     |
| trigger                     | 4/5     |
| grounded                    | 5/5     |
| avg turns                   | 9.4     |
| cumulative input tokens/run | ~220K   |
| cost/run                    | ~$0.025 |

Input is ~95% of cost; each late turn resends ~30–45K tokens of old
tool results.

Stage 5 goal: cut input tokens while keeping PASS ≈ 4/5.

### 35. Message Batches: async, per-request failures, out-of-order results
Day1Batch: 6 requests (5 log lines + 1 with an invalid model),
classified HIGH/MEDIUM/LOW.

What happened:
- The batch was ACCEPTED at create (processing=6), even with
  the invalid model.
- The bad request failed only after the batch ENDED, as one
  ERRORED result (not_found_error); the other 5 succeeded.
- ~2.5 min to ENDED for 6 tiny requests. Batches suit offline
  and overnight work, not interactive use.
- Results arrived out of order:
  [line-6, line-2, line-3, line-5, line-1, line-4].

Mechanics:
- MessageBatch is a snapshot; the id is the stable handle.
  Poll with retrieve(id) to get a fresh state each time.
- Compare status with ENDED.equals(...), not !=
  (an SDK value object, not a Java enum).
- Back off between polls (10s, 20s, 40s ... capped).
- Fetch results once, after ENDED; map them back by custom_id.
- Handle all 4 outcomes: succeeded / errored / canceled / expired.
  (isValid() is SDK object validation, not an outcome.)

Unplanned lessons:
- Two placeholder lines ("<ERR-4013 line from grep>") went
  through and counted as SUCCEEDED; the model just asked for
  the real line. A batch checks execution, not quality.
  Guards: validate inputs before submit; constrain the output
  (enum schema via outputConfig) so bad answers fail loudly.
- Output tokens dominated cost: 476 in vs 424 out, and output
  costs 5x per token. The placeholder replies cost more than the
  4 real answers. A one-word answer still used 84 output tokens
  (hidden thinking counts as output).
- Cost: ~$0.00026 at full price, ~$0.00013 with the batch
  discount (about 50%). ModelPricing needs a batch flag, or the
  telemetry overstates the cost.

Triage insight: the campaign line (the real root cause) was
classified LOW. Judging lines one at a time can't do triage;
importance comes from correlation and timing (traffic before
and after 13:54), which is what the agent does.

### 36. Claude Code: CLAUDE.md (guidance) vs hooks (enforcement)

CLAUDE.md:
- Bootstrapped with /init, then reviewed by hand. /init got
  several facts wrong, stated confidently: SDK version, exec.args
  order, Stage 5A behaviour, token growth numbers, stage count.
  A wrong line in CLAUDE.md is worse than none: it loads into
  every session and is treated as true.
- Keep: build/run commands, ground-truth rule, experiment rules,
  hard-won API gotchas (temperature 400, hidden thinking tokens,
  truncation feedback, few-shot leakage).
- Cut: data flow (Claude can read the code), inventories that go
  stale (stage list, file list).
- Rule: describe conventions, not inventories. Test for each line:
  would Claude make a wrong or slower move without it?
- Model IDs in args are API IDs (claude-haiku-5-5), not Java
  constant names (CLAUDE_HAIKU_5_5).
- CLAUDE.md loads at session start; changes need a new session.

Hook (.claude/settings.json, PreToolUse, matcher Edit|Write|Bash):
- Script reads the tool JSON on stdin.
  Edit/Write: tool_input.file_path matches a protected path → block.
  Bash: tool_input.command has a protected path AND a write op
  (>, sed -i, tee, rm, mv, cp, truncate, dd) → block.
  Block = reason on stderr + exit 2 (the tool doesn't run; the
  reason goes to Claude). Otherwise exit 0.
- protected='(data/logs|answers)/|\.claude/(hooks/|settings\.json)'
  One regex used by both checks (grep -Eq = match anywhere, quiet).
- The Bash check is a heuristic that errs towards blocking
  (`2>/dev/null` on a read is blocked too). A blocked read is
  acceptable; a modified baseline is not.

How it was tested:
1. CLAUDE.md alone made Claude refuse every edit ("go ahead",
   "use sed"), so the hook never fired. To test a backstop, get past
   the first defence.
2. Unit-tested the script with fake JSON: exit 2 + message for
   data/logs paths and write commands, exit 0 for src/ and grep.
3. Live test, CLAUDE.md rule removed, matcher Edit|Write:
   Claude chose Bash (echo >> data/logs/payments.log), the hook
   didn't see it, and THE GROUND TRUTH WAS MODIFIED (restored
   with git restore). Any eval before the restore would have
   scored