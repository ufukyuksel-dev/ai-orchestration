"""Token profile of one agent run, from the agent CLI's own event stream.

Measured (from the runtime's usage records): input, cache-write input, cached (cache-read) input, output,
model turns, cost when reported. Derived from the event stream: tool calls per tool and the size of what
each tool returned (characters; a proxy, not tokens).
"""
from __future__ import annotations

import json
from collections import Counter


def _text_len(content) -> int:
    if content is None:
        return 0
    if isinstance(content, str):
        return len(content)
    if isinstance(content, list):
        return sum(_text_len(c.get("text") if isinstance(c, dict) else c) for c in content)
    if isinstance(content, dict):
        return _text_len(content.get("text") or json.dumps(content))
    return len(str(content))


def _tool_group(name: str) -> str:
    if name.startswith("mcp__"):
        return "mcp:" + name.split("__", 2)[-1]
    return name


def profile_claude(events: list[dict]) -> dict:
    calls: Counter = Counter()
    result_chars: Counter = Counter()
    names_by_id: dict[str, str] = {}
    usage, turns, cost, final = {}, 0, None, None
    for e in events:
        if e.get("type") == "assistant":
            for c in (e.get("message") or {}).get("content") or []:
                if c.get("type") == "tool_use":
                    group = _tool_group(c.get("name", "?"))
                    calls[group] += 1
                    names_by_id[c.get("id")] = group
        elif e.get("type") == "user":
            for c in (e.get("message") or {}).get("content") or []:
                if isinstance(c, dict) and c.get("type") == "tool_result":
                    result_chars[names_by_id.get(c.get("tool_use_id"), "?")] += _text_len(c.get("content"))
        elif e.get("type") == "result":
            # A session can end more than once: a background task finishing wakes it for another turn. Each result
            # carries the usage of its own segment (summed here); the cost is cumulative (the last one counts).
            for key, value in (e.get("usage") or {}).items():
                if isinstance(value, int):
                    usage[key] = usage.get(key, 0) + value
            turns += e.get("num_turns") or 0
            cost = e.get("total_cost_usd")
            final = e.get("result")
    fresh = usage.get("input_tokens", 0)
    cache_write = usage.get("cache_creation_input_tokens", 0)
    cache_read = usage.get("cache_read_input_tokens", 0)
    output = usage.get("output_tokens", 0)
    return {"inputTokens": fresh + cache_write, "cachedInputTokens": cache_read, "outputTokens": output,
            "totalTokens": fresh + cache_write + cache_read + output, "turns": turns, "costUsd": cost,
            "toolCalls": dict(calls), "toolResultChars": dict(result_chars),
            "mcpCalls": sum(v for k, v in calls.items() if k.startswith("mcp:")),
            "finalAnswerChars": len(final or "")}


def profile_codex(events: list[dict]) -> dict:
    calls: Counter = Counter()
    result_chars: Counter = Counter()
    inp = cached = out = turns = 0
    final = ""
    for e in events:
        t = e.get("type")
        if t == "turn.completed":
            u = e.get("usage") or {}
            cached += u.get("cached_input_tokens", 0)
            inp += u.get("input_tokens", 0) - u.get("cached_input_tokens", 0)
            out += u.get("output_tokens", 0)
            turns += 1
        elif t == "item.completed":
            item = e.get("item") or {}
            kind = item.get("type") or item.get("item_type") or "?"
            if kind == "mcp_tool_call":
                group = "mcp:" + str(item.get("tool"))
                calls[group] += 1
                result_chars[group] += _text_len(item.get("result"))
            elif kind == "command_execution":
                calls["Bash"] += 1
                result_chars["Bash"] += len(item.get("aggregated_output") or "")
            elif kind == "file_change":
                calls["Edit"] += 1
            elif kind == "agent_message":
                final = item.get("text") or final
    return {"inputTokens": inp, "cachedInputTokens": cached, "outputTokens": out,
            "totalTokens": inp + cached + out, "turns": turns, "costUsd": None,
            "toolCalls": dict(calls), "toolResultChars": dict(result_chars),
            "mcpCalls": sum(v for k, v in calls.items() if k.startswith("mcp:")),
            "finalAnswerChars": len(final or "")}


def profile_copilot(events: list[dict]) -> dict:
    """GitHub Copilot CLI (`--output-format json` events + the `--usage-output-file` the harness appends as `_usage`).
    Usage per model: inputTokens = fresh + cache write + cache read (checked: 4 + 21926 + 21491 = 43421)."""
    calls: Counter = Counter()
    result_chars: Counter = Counter()
    names_by_id: dict[str, str] = {}
    memory_searches = refused = turns = 0
    final, usage = "", None
    for e in events:
        data = e.get("data") or {}
        t = e.get("type")
        if t == "tool.execution_start":
            name = str(data.get("toolName") or "?")
            names_by_id[data.get("toolCallId")] = name
            calls[name] += 1
            if "ai_orch memory search" in json.dumps(data.get("arguments") or {}):
                memory_searches += 1
        elif t == "tool.execution_complete":
            content = (data.get("result") or {}).get("content")
            result_chars[names_by_id.get(data.get("toolCallId"), "?")] += _text_len(content)
            if '"refused": true' in str(content):
                refused += 1
        elif t == "assistant.turn_end":
            turns += 1
        elif t == "assistant.message" and data.get("content"):
            final = data["content"]
        elif t == "_usage":
            usage = e.get("usage")
    fresh = cache_write = cache_read = output = 0
    for metrics in ((usage or {}).get("modelMetrics") or {}).values():
        u = metrics.get("usage") or {}
        cache_read += u.get("cacheReadTokens", 0)
        cache_write += u.get("cacheWriteTokens", 0)
        fresh += u.get("inputTokens", 0) - u.get("cacheReadTokens", 0) - u.get("cacheWriteTokens", 0)
        output += u.get("outputTokens", 0)
    return {"inputTokens": fresh + cache_write if usage else None, "cachedInputTokens": cache_read if usage else None,
            "outputTokens": output if usage else None,
            "totalTokens": fresh + cache_write + cache_read + output if usage else None, "turns": turns,
            "costUsd": None, "premiumRequests": (usage or {}).get("totalPremiumRequestCost"),
            "toolCalls": dict(calls), "toolResultChars": dict(result_chars), "mcpCalls": 0,
            "memorySearches": memory_searches, "memorySearchesRefused": refused,
            "finalAnswerChars": len(final or "")}


def profile(agent: str, events: list[dict]) -> dict:
    if agent == "copilot":
        return profile_copilot(events)
    return profile_claude(events) if agent == "claude" else profile_codex(events)
