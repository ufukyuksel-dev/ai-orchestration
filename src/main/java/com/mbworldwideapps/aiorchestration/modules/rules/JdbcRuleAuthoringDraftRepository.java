package com.mbworldwideapps.aiorchestration.modules.rules;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcRuleAuthoringDraftRepository implements RuleAuthoringDraftRepository {

    private final JdbcTemplate jdbcTemplate;

    private final RowMapper<RuleAuthoringDraft> mapper = (rs, rowNum) -> new RuleAuthoringDraft(
            rs.getObject("id", UUID.class),
            rs.getString("project_key"),
            rs.getString("candidate_json"),
            rs.getString("candidate_hash"),
            rs.getString("created_by"),
            instant(rs, "created_at"),
            rs.getObject("promoted_rule_id", UUID.class),
            rs.getObject("promoted_rule_version", Integer.class),
            rs.getString("promoted_approval_hash"),
            rs.getString("promoted_request_hash"),
            instant(rs, "promoted_at"));

    public JdbcRuleAuthoringDraftRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void insert(RuleAuthoringDraft draft) {
        jdbcTemplate.update("""
                INSERT INTO rule_authoring_drafts
                    (id, project_key, candidate_json, candidate_hash, created_by, created_at)
                VALUES (?, ?, ?::jsonb, ?, ?, ?)
                """, draft.id(), draft.projectKey(), draft.candidateJson(), draft.candidateHash(),
                draft.createdBy(), Timestamp.from(draft.createdAt()));
    }

    @Override
    public Optional<RuleAuthoringDraft> findById(UUID draftId) {
        return first(jdbcTemplate.query("""
                SELECT * FROM rule_authoring_drafts WHERE id = ?
                """, mapper, draftId));
    }

    @Override
    public Optional<RuleAuthoringDraft> findByIdForUpdate(UUID draftId) {
        return first(jdbcTemplate.query("""
                SELECT * FROM rule_authoring_drafts WHERE id = ? FOR UPDATE
                """, mapper, draftId));
    }

    @Override
    public boolean bindPromotion(UUID draftId, String expectedCandidateHash, UUID ruleId, int ruleVersion,
            String approvalContentHash, String promotionRequestHash, Instant promotedAt) {
        return jdbcTemplate.update("""
                UPDATE rule_authoring_drafts
                SET promoted_rule_id = ?, promoted_rule_version = ?, promoted_approval_hash = ?,
                    promoted_request_hash = ?, promoted_at = ?
                WHERE id = ? AND candidate_hash = ? AND promoted_rule_id IS NULL
                """, ruleId, ruleVersion, approvalContentHash, promotionRequestHash,
                Timestamp.from(promotedAt), draftId, expectedCandidateHash) == 1;
    }

    private static Optional<RuleAuthoringDraft> first(List<RuleAuthoringDraft> values) {
        return values.stream().findFirst();
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }
}
