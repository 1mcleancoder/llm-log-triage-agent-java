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
   Lesson: Examples should show the shape using a different incident. They also set conventions silently (first_seen = first ERROR line). Cost: two examples added ~2,200 input tokens (+32%) per request.

9. About: Evidence validation
   What you expected: if every evidence line exists in the logs, the evidence is good
   What happened: a "health check ok" line passed the check but supported nothing
   Why: a verbatim check proves the evidence is grounded (not invented), not that it's relevant. Relevance needs a human review or an LLM judge (stage 9).