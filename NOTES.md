WIP: Stage 2 of 9: structured output. Next: tool use.

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

25. About:
    What you expected:
    What happened:
    Why:
    (leave last one empty so it is easy to fill next time)
