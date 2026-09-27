# Memory delete etiquette

> On-demand contract. Load only when this workflow is needed. Moved verbatim from `CLAUDE.md`/`AGENTS.md`; where it conflicts with the lean session contract (`session.bootstrap`, offline rule, `memory.learn`), the session contract wins.

## MCP Memory Delete Etiquette

When the human asks to delete a memory record, do not choose a deletion mode by default. Ask whether they want archive or permanent delete:

```text
Arşivleyeyim mi (geri alınabilir, audit kalır) yoksa kalıcı olarak sileyim mi (geri alınamaz)?
```

- Archive: call `memory.delete(memoryId, mode="archive", reason=...)`.
- Permanent delete: call `memory.delete(memoryId, mode="hard_delete", reason=..., humanConfirmed=true, humanTurnRef=..., humanRawText=...)`.
- Hard delete geri alınamaz: DB row ve per-memory event timeline silinir; sadece `mcp_access_log` tombstone kalır, o da audit insert başarılı olursa.

Never set `humanConfirmed=true` unless the human explicitly authored the permanent-delete confirmation in the current conversation.
