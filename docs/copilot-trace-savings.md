# Copilot trace: estimated saving per delivered card

The Copilot hooks write a local trace (`skills/copilot/ai-orchestration-memory/scripts/copilot_trace.py`, contract v1:
`${COPILOT_HOME:-~/.copilot}/ai-orch/trace/YYYY-MM-DD.jsonl`). The trace holds only measured facts: which cards an
`inject` delivered, how many characters it added, the tool calls and stops of each request. A reader (the Copilot Team
panel) turns that into an **estimated** gain with adjustable coefficients. This note explains where the defaults come
from and how weak the evidence is.

## What the bench can and cannot say

Source: `bench/copilot-results.md` and its raw runs (`bench/.work/results/val-P2-*`, `val-G-*`, `java1-*`,
`java23-*`; tokens from Copilot's usage database; cards counted from the prompt hook's context in each B transcript).
Only tasks both arms completed are used.

| group | tasks | B / A (each) | mean |
|---|---|---|---|
| first tasks, no memory yet (structural effect only: compact agent, test-command advice, learning in the answer) | 4 | 1.09, 0.73, 0.67, 1.00 | **0.873** |
| follow-ups that got ≥ 1 full card at the prompt (7 full cards in 6 tasks) | 6 | 0.53, 0.28, 0.56, 0.97, 0.55, 1.52 | 0.735 |
| follow-ups that got no full card (pointers or nothing at the prompt) | 6 | 0.58, 0.65, 0.72, 0.53, 0.45, 0.15 | 0.512 |

Saving beyond the structural share, `A × 0.873 − B`, divided by the full cards of that task:

- per task: +105k, +238k, +338k, −45k (2 cards), +382k, −758k (Java f1t3, the arm with the card cost more);
- pooled: **≈ 31k tokens per full card**; median ≈ 172k; spread from −758k to +382k.

**The bench does not isolate a per-card effect.** Follow-ups without a full card saved *more* than follow-ups with one
(median beyond structural ≈ 400k), so chain effects (step-time memory, pointers, the agent's own memory searches, task
order) dominate; and one task varies by ±25–40 % between runs of the same arm (plain p1t1: 300k vs 510k), which is
larger than any plausible per-card effect. n = 6 per group, no confidence interval.

Cleaner but smaller evidence: the Kotlin pilot compared the product with and without memory (B vs Bnomem, same
agent and hooks), so the difference is memory alone: 105k and 129k over the 2–3 follow-up tasks of each chain. Card
counts per task were not recorded; with at least one card per follow-up this is **at most ≈ 50–65k per delivered
card**. One repetition, a pilot.

Mechanism, from the same report: with a card, reads before the first edit fell from 4–7 to 2–3, and in the pilot
from 14–17 searches to none. One avoided early call costs about its input (≈ 11–20k with the compact agent; the first
call is ≈ 10.7k), and the tool output it would have added stays out of every later call (≈ 1–3k × the remaining calls).
Two to four avoided calls: ≈ 25–60k; one small avoided read: ≈ 5–10k.

## Defaults

| coefficient | default | range | basis |
|---|---|---|---|
| `savingsPerCard` (full card) | **15 000** (kept) | **5 000 – 40 000** (kept) | below every central estimate above (bench pooled ≈ 31k, mechanism ≈ 25k for two avoided calls, pilot ≤ 50–65k): an estimate shown to the user should not overstate; the low end is one avoided small read, the high end four avoided calls, short of the noisy pilot upper bound |
| pointer card factor | 0.1 (kept) | — | a pointer delivers nothing until the agent opens it; the one large pointer win (Java f1t2: 70 → 20 calls) is n = 1 and is not attributed to the pointer alone |
| `learnTurnCost` | **20 000 (proposed; contract says 2 000)** | 10 000 – 30 000 | the report measures the one-reply learning turn at **≈ 20k**: it is a full model call with the whole context resent, not 2k |
| `fixBonus` | 20 000 (kept) | — | no bench measurement; one avoided fix loop is a few calls, same order as above |

The cost side needs no coefficient: `injected.tokensEst × (1 + tool events of that request)` is measured from the
trace (Copilot resends the injected context with every call).

## What would make this a measurement

Record, per bench task, the trace itself (cards and pointers delivered at the prompt and at steps) and run a
`Bnomem` arm on the main suites, two or more repetitions: then `B − Bnomem` per delivered card is the coefficient, with
an interval.
