| Agent | Arm | Success | Follow-up tasks: tokens per success | First task (learns): mean tokens | All tasks: tokens per success | All tasks: $ per success | Follow-up turns |
|---|---|---|---|---|---|---|---|
| Claude Code | without | 12/12 | 1.12M | 1.39M | 1.21M | 0.53 | 27.9 |
| Claude Code | with AI Orchestration | 12/12 | 0.90M | 1.56M | 1.12M | 0.49 | 24.1 |
| | **difference** | | **-19.6%** | +11.8% | **-7.6%** | -8.1% | |
| Codex | without | 12/12 | 0.57M | 0.57M | 0.57M | — | — |
| Codex | with AI Orchestration | 12/12 | 0.36M | 0.60M | 0.44M | — | — |
| | **difference** | | **-36.7%** | +4.4% | **-22.9%** | — | |

Spring Petclinic (holdout suite), 2 task families × 3 related tasks, 2 repetitions, cumulative learning chain · models: default, sonnet · 2026-09-27 · development measurement: fixes were found on these tasks
