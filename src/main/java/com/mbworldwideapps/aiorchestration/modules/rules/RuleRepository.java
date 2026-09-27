package com.mbworldwideapps.aiorchestration.modules.rules;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Persistence port for the rule authority. rule_versions and rule_target_bindings
 * are append-only: this interface intentionally defines no update or delete
 * operations for them; the database additionally rejects mutation via triggers.
 */
public interface RuleRepository {

    void insertDefinition(RuleDefinition definition);

    void insertVersion(RuleVersion version);

    void insertBindings(List<RuleTargetBinding> bindings);

    void insertSelectorGroups(List<RuleSelectorGroupDefinition> groups);

    void insertCheckDefinitions(List<RuleCheckDefinition> checks);

    void insertScopeAssignments(List<RuleScopeAssignment> assignments);

    void insertLifecycleEvent(RuleLifecycleEvent event);

    Optional<RuleLifecycleEvent> findLifecycleEvent(RuleLifecycleAction action, String approvalContentHash);

    Optional<RuleDefinition> findDefinition(UUID ruleId);

    Optional<RuleDefinition> findByOriginMemoryId(UUID memoryId);

    Optional<RuleVersion> findVersion(UUID ruleId, int version);

    List<RuleTargetBinding> findBindings(UUID ruleId, int version);

    List<RuleSelectorGroupDefinition> findSelectorGroups(UUID ruleId, int version);

    List<RuleCheckDefinition> findCheckDefinitions(UUID ruleId, int version);

    List<RuleScopeAssignment> findScopeAssignments(UUID ruleId, int version);

    List<RuleDefinition> findVisibleActive(String projectKey);

    /**
     * Maximum visible active glob-rule count after adding one candidate. A null
     * project key means global reach, so every registered/known project scope is
     * considered. Callers hold {@link #lockEffectiveSequences(List)} while using
     * this value.
     */
    int maxProjectedActiveGlobRules(String projectKey, UUID excludedRuleId);

    /** Compare-and-set advance of an active definition to an already inserted immutable version. */
    boolean advanceDefinition(UUID ruleId, int expectedCurrentVersion, int newVersion,
            UUID newOriginMemoryId, String expectedProjectKey, RuleStatus expectedStatus);

    /** Compare-and-set deprecation; returns false when the caller observed stale state. */
    boolean deprecateDefinition(UUID ruleId, int expectedCurrentVersion);

    long currentChangeSeq();

    long currentGlobalEffectiveSeq();

    /**
     * Returns the project reach sequence. A project first observed after V47 has
     * no local-rule history and therefore reads as zero; the first project-local
     * writer materializes and locks its row.
     */
    long currentProjectEffectiveSeq(String projectKey);

    /**
     * Locks global reach first, then distinct project rows in lexical order, and
     * finally the legacy total sequence. Missing project rows start at zero because
     * they have no project-local rule history.
     */
    void lockEffectiveSequences(List<String> projectKeys);

    /** Bumps legacy total plus either global reach or the supplied project reaches. */
    RuleEffectiveSequence bumpEffectiveSequences(boolean globalReachChanged, List<String> projectKeys);
}
