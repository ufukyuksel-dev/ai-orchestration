-- STALE_EVIDENCE on an active discovery came only from edits (any edit used to mark the profile stale).
-- Profiles now follow their item's status, so realign those; stale items are reconciled after startup.
UPDATE memory_learning_profiles p
SET usefulness_state = 'ACTIVE', learning_revision = p.learning_revision + 1, updated_at = now()
FROM memory_items mi
WHERE mi.id = p.memory_id AND mi.status = 'active' AND p.usefulness_state = 'STALE_EVIDENCE';
