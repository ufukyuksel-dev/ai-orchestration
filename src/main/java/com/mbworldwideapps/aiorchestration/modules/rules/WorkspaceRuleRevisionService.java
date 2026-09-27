package com.mbworldwideapps.aiorchestration.modules.rules;

import com.mbworldwideapps.aiorchestration.modules.memoryai.WorkspaceRuleOriginService;
import com.mbworldwideapps.aiorchestration.modules.workspace.PanelText;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/** Text-only revisions preserve the existing executable selectors, checks, scope and origin. */
@Service
public class WorkspaceRuleRevisionService {
    public record Edit(
            @Min(1) int version,
            @NotBlank @Size(max = 64) String contentHash,
            @NotBlank @Size(max = 16384) String statement,
            @Size(max = 4096) String rationale,
            UUID originId,
            @Size(max = 64) String originHash) {}

    public record Preview(Edit edit, RulePromotionPreview confirmation) {}

    public record Approval(
            @NotNull @Valid Edit edit,
            @NotBlank @Size(max = 64) String approvalContentHash,
            @NotBlank @Size(max = 64) String confirmationCardHash,
            @NotBlank @Size(max = 128) String workflowContractVersion,
            @NotBlank @Size(max = 8192) String humanRawText,
            @NotBlank @Size(max = 512) String humanTurnRef) {}

    private final RuleRepository repository;
    private final RulePromotionService promotion;
    private final WorkspaceRuleOriginService origins;

    public WorkspaceRuleRevisionService(
            RuleRepository repository,
            RulePromotionService promotion,
            WorkspaceRuleOriginService origins) {
        this.repository = repository;
        this.promotion = promotion;
        this.origins = origins;
    }

    public RuleDefinition definition(UUID id) {
        return repository
                .findDefinition(id)
                .orElseThrow(() -> new IllegalArgumentException(PanelText.t("Rule not found", "Kural bulunamadı")));
    }

    @Transactional
    public Preview preview(UUID id, String statement, String rationale, String actor) {
        var d = definition(id);
        if (d.status() != RuleStatus.ACTIVE)
            throw new IllegalArgumentException(PanelText.t("Only active rules can be edited",
                    "Yalnız aktif kurallar düzenlenebilir"));
        var v = repository.findVersion(id, d.currentVersion()).orElseThrow();
        var edit =
                new Edit(
                        v.version(),
                        v.contentHash(),
                        statement,
                        rationale,
                        v.originMemoryId(),
                        v.originContentHash());
        if (v.originMemoryId() != null) {
            var origin =
                    origins.draft(
                            id, v.version(), v.originMemoryId(), d.projectKey(), statement, actor);
            edit =
                    new Edit(
                            v.version(),
                            v.contentHash(),
                            statement,
                            rationale,
                            origin.id(),
                            origin.contentHash());
        }
        return new Preview(
                edit,
                promotion.previewVersion(id, v.version(), v.contentHash(), candidate(id, edit)));
    }

    @Transactional
    public RuleVersion activate(UUID id, Approval approval, String actor) {
        if (approval == null
                || approval.edit() == null
                || approval.humanRawText() == null
                || approval.humanRawText().isBlank())
            throw new IllegalArgumentException(PanelText.t("An approval text is required for the change shown",
                    "Gösterilen değişiklik için onay metni gereklidir"));
        var edit = approval.edit();
        var activated =
                promotion.promoteVersion(
                        id,
                        edit.version(),
                        edit.contentHash(),
                        new PromotionRequest(
                                candidate(id, edit),
                                approval.approvalContentHash(),
                                approval.confirmationCardHash(),
                                approval.workflowContractVersion(),
                                new RuleHumanApprovalEvidence(
                                        actor,
                                        approval.humanTurnRef(),
                                        approval.humanRawText(),
                                        true,
                                        1.0)));
        if (edit.originId() != null) {
            origins.archiveSuperseded(id, edit.version(), edit.originId(), actor);
        }
        return activated;
    }

    private RulePromotionCandidate candidate(UUID id, Edit edit) {
        var d = definition(id);
        var v =
                repository
                        .findVersion(id, edit.version())
                        .orElseThrow(() -> new IllegalArgumentException(PanelText.t("Rule version not found",
                                "Kural sürümü bulunamadı")));
        if (!v.contentHash().equals(edit.contentHash()))
            throw new IllegalArgumentException(PanelText.t("The rule version changed; open it again",
                    "Kural sürümü değişti; tekrar açın"));
        return new RulePromotionCandidate(
                edit.originId(),
                edit.originHash(),
                d.projectKey(),
                edit.statement(),
                edit.rationale(),
                v.enforcement(),
                v.appliesAll(),
                v.detectorType(),
                v.detectorConfig(),
                repository.findBindings(id, v.version()).stream()
                        .map(
                                b ->
                                        new RulePromotionCandidate.TargetBindingRequest(
                                                b.kind(), b.targetKey()))
                        .toList(),
                repository.findSelectorGroups(id, v.version()).stream()
                        .map(
                                g ->
                                        new RuleSelectorGroup(
                                                g.groupKey(),
                                                g.predicates().stream()
                                                        .map(
                                                                p ->
                                                                        new RuleSelectorPredicate(
                                                                                p.polarity(),
                                                                                p.field(),
                                                                                p.operator(),
                                                                                p.values()))
                                                        .toList()))
                        .toList(),
                repository.findCheckDefinitions(id, v.version()).stream()
                        .map(
                                c ->
                                        new RuleCheckSpec(
                                                c.phase(),
                                                c.checkerType(),
                                                c.severity(),
                                                c.config()))
                        .toList(),
                v.originProvenance());
    }
}
