package com.winter.airesumeoptimizer.module.workspace.service.impl;

import com.winter.airesumeoptimizer.common.exception.BusinessException;
import com.winter.airesumeoptimizer.infra.ai.AiGatewayRequest;
import com.winter.airesumeoptimizer.infra.ai.AiClientException;
import com.winter.airesumeoptimizer.infra.ai.AiCompletionResult;
import com.winter.airesumeoptimizer.infra.ai.AiFailureCode;
import com.winter.airesumeoptimizer.infra.ai.AiGateway;
import com.winter.airesumeoptimizer.infra.ai.AiGatewayException;
import com.winter.airesumeoptimizer.infra.ai.AiGatewaySupport;
import com.winter.airesumeoptimizer.infra.ai.AiInvocationContext;
import com.winter.airesumeoptimizer.infra.ai.AiSelectionSnapshot;
import com.winter.airesumeoptimizer.module.evidence.service.EvidenceMatchService;
import com.winter.airesumeoptimizer.module.evidence.vo.EvidenceAnalysisResultVO;
import com.winter.airesumeoptimizer.module.workspace.dto.BulletRewriteOutputDTO;
import com.winter.airesumeoptimizer.module.workspace.dto.BulletRewritePromptDTO;
import com.winter.airesumeoptimizer.module.workspace.dto.ResumeDocumentBulletDTO;
import com.winter.airesumeoptimizer.module.workspace.dto.ResumeDocumentDTO;
import com.winter.airesumeoptimizer.module.workspace.dto.ResumeDocumentEntryDTO;
import com.winter.airesumeoptimizer.module.workspace.dto.ResumeDocumentSectionDTO;
import com.winter.airesumeoptimizer.module.workspace.dto.RewriteFactValidationResult;
import com.winter.airesumeoptimizer.module.workspace.dto.WorkspaceBulletSuggestRequestDTO;
import com.winter.airesumeoptimizer.module.workspace.enums.BulletSuggestIntent;
import com.winter.airesumeoptimizer.module.workspace.service.BulletRewriteOutputParser;
import com.winter.airesumeoptimizer.module.workspace.service.BulletRewritePromptService;
import com.winter.airesumeoptimizer.module.workspace.service.BulletRewriteRefusedException;
import com.winter.airesumeoptimizer.module.workspace.service.BulletRewriteService;
import com.winter.airesumeoptimizer.module.workspace.service.RewriteFactValidator;
import com.winter.airesumeoptimizer.module.workspace.service.RewriteMaterialityGate;
import com.winter.airesumeoptimizer.module.workspace.service.WorkspaceContentService;
import com.winter.airesumeoptimizer.module.optimization.service.OptimizationTaskService;
import com.winter.airesumeoptimizer.module.workspace.vo.WorkspaceBulletSuggestionVO;
import com.winter.airesumeoptimizer.module.workspace.vo.WorkspaceContentVO;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Autowired;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class BulletRewriteServiceImpl implements BulletRewriteService {

    private static final Logger log = LoggerFactory.getLogger(BulletRewriteServiceImpl.class);

    private static final Pattern REQUEST_ID_PATTERN = Pattern.compile(
            "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");

    private final WorkspaceContentService workspaceContentService;
    private final EvidenceMatchService evidenceMatchService;
    private final BulletRewritePromptService bulletRewritePromptService;
    private final BulletRewriteOutputParser bulletRewriteOutputParser;
    private final RewriteFactValidator rewriteFactValidator;
    private final RewriteMaterialityGate rewriteMaterialityGate;
    private final AiGateway aiGateway;
    private final OptimizationTaskService optimizationTaskService;

    public BulletRewriteServiceImpl(
            WorkspaceContentService workspaceContentService,
            EvidenceMatchService evidenceMatchService,
            BulletRewritePromptService bulletRewritePromptService,
            BulletRewriteOutputParser bulletRewriteOutputParser,
            RewriteFactValidator rewriteFactValidator,
            AiGateway aiGateway) {
        this(
                workspaceContentService,
                evidenceMatchService,
                bulletRewritePromptService,
                bulletRewriteOutputParser,
                rewriteFactValidator,
                new RewriteMaterialityGateImpl(),
                aiGateway,
                null);
    }

    @Autowired
    public BulletRewriteServiceImpl(
            WorkspaceContentService workspaceContentService,
            EvidenceMatchService evidenceMatchService,
            BulletRewritePromptService bulletRewritePromptService,
            BulletRewriteOutputParser bulletRewriteOutputParser,
            RewriteFactValidator rewriteFactValidator,
            RewriteMaterialityGate rewriteMaterialityGate,
            AiGateway aiGateway,
            OptimizationTaskService optimizationTaskService) {
        this.workspaceContentService = workspaceContentService;
        this.evidenceMatchService = evidenceMatchService;
        this.bulletRewritePromptService = bulletRewritePromptService;
        this.bulletRewriteOutputParser = bulletRewriteOutputParser;
        this.rewriteFactValidator = rewriteFactValidator;
        this.rewriteMaterialityGate = rewriteMaterialityGate;
        this.aiGateway = aiGateway;
        this.optimizationTaskService = optimizationTaskService;
    }

    @Override
    public WorkspaceBulletSuggestionVO suggestBulletRewrite(
            Long userId, Long optimizationTaskId, WorkspaceBulletSuggestRequestDTO request) {
        validateRequest(request);

        // 旧版兼容任务没有正式证据分析：岗位定向改写必须 fail closed，
        // 不得把不可追溯的历史输出当作事实依据。
        EvidenceAnalysisResultVO evidenceAnalysis =
                evidenceMatchService.getResult(userId, optimizationTaskId);
        if (evidenceAnalysis == null) {
            throw new BusinessException(409, "该任务没有正式证据分析，暂不能启用岗位定向改写");
        }

        // Phase 4 seam：任务归属、版本链校验与 TARGET 文档读取全部复用既有服务；
        // 跨用户访问、任务未完成、版本链异常都在此 fail closed。
        WorkspaceContentVO content = workspaceContentService.getContent(userId, optimizationTaskId);
        AiSelectionSnapshot selection = optimizationTaskService == null
                ? null
                : optimizationTaskService.getExecutionContext(userId, optimizationTaskId).aiSelection();
        if (selection == null) {
            selection = AiGatewaySupport.selectionForNewTask(
                    aiGateway,
                    userId,
                    "BULLET_REWRITE");
        }
        long currentRevision = content.getRevision();
        if (!request.getBaseRevision().equals(currentRevision)) {
            throw stale("简历内容已有更新的版本，本次建议请求已过期");
        }

        ResumeDocumentBulletDTO bullet = findBullet(content.getDocument(), request.getBulletId());
        if (bullet == null) {
            throw stale("目标要点不存在或已被删除，本次建议请求已过期");
        }
        String persistedOriginal = bullet.getText() == null ? "" : bullet.getText().strip();
        if (persistedOriginal.isBlank()) {
            throw stale("目标要点内容为空，本次建议请求已过期");
        }
        String expectedHash = sha256Hex(persistedOriginal);
        if (!expectedHash.equalsIgnoreCase(request.getOriginalTextHash())
                || !expectedHash.equals(sha256Hex(request.getOriginalText().strip()))) {
            throw stale("目标要点已被修改，本次建议请求已过期");
        }

        BulletRewritePromptDTO prompt = bulletRewritePromptService.buildPrompt(
                request.getIntent(),
                request.getIntent() == BulletSuggestIntent.CUSTOM ? request.getUserInstruction() : null,
                persistedOriginal,
                evidenceAnalysis);

        log.info("Bullet rewrite started: userId={}, optimizationTaskId={}, intent={}, revision={}",
                userId, optimizationTaskId, request.getIntent(), currentRevision);

        String aiOutput;
        String modelName = selection != null && !selection.model().isBlank()
                ? selection.model()
                : "unknown";
        try {
            // 平台可信策略进 SYSTEM；简历 / 岗位 / 用户要求等不可信数据进 USER 数据区。
            AiCompletionResult completion = AiGatewaySupport.complete(
                    aiGateway,
                    new AiInvocationContext(userId, optimizationTaskId, "BULLET_REWRITE", selection),
                    new AiGatewayRequest(
                            "BULLET_REWRITE",
                            prompt.getSystemPolicy(),
                            prompt.getUserContent()));
            aiOutput = completion.text();
            modelName = completion.model();
        } catch (AiGatewayException exception) {
            log.warn("Bullet rewrite Gateway call failed: model={}, code={}",
                    modelName,
                    exception.getFailureCode());
            throw exception;
        } catch (AiClientException exception) {
            // Provider errors may include echoed untrusted content; do not retain them.
            log.warn("Bullet rewrite AI call failed: model={}, exceptionType={}",
                    modelName,
                    exception.getClass().getSimpleName());
            throw new BusinessException(502, "AI 服务暂时不可用，请稍后重试");
        }

        BulletRewriteOutputDTO output;
        try {
            output = bulletRewriteOutputParser.parse(aiOutput);
        } catch (BulletRewriteRefusedException exception) {
            log.info("Bullet rewrite refused by AI: optimizationTaskId={}", optimizationTaskId);
            return rejected(request, currentRevision, persistedOriginal,
                    WorkspaceBulletSuggestionVO.REJECT_CODE_REFUSED,
                    "AI 没有返回可用的改写候选，请重新生成或继续手工编辑。", modelName);
        } catch (BusinessException exception) {
            throw new AiGatewayException(AiFailureCode.SCHEMA_INVALID, "AI 返回结果格式异常");
        }

        // 事实审查只以当前 Bullet 原文为基线：不跨 Bullet 搬运事实。
        // 内容变化是给用户的 review advisory；只有技术安全问题仍然 hard reject。
        RewriteFactValidationResult factCheck =
                rewriteFactValidator.validate(persistedOriginal, output.suggestedText());
        if (!factCheck.passed() && factCheck.code().isTechnicalSafetyFailure()) {
            log.info("Bullet rewrite rejected by technical safety gate: optimizationTaskId={}, code={}",
                    optimizationTaskId, factCheck.code());
            return rejected(request, currentRevision, persistedOriginal,
                    factCheck.code().name(), factCheck.message(), modelName);
        }

        if (rewriteMaterialityGate.isLowValueChange(persistedOriginal, output.suggestedText())) {
            log.info("Bullet rewrite rejected as low-value change: optimizationTaskId={}", optimizationTaskId);
            return rejected(request, currentRevision, persistedOriginal,
                    WorkspaceBulletSuggestionVO.REJECT_CODE_LOW_VALUE_CHANGE,
                    "这次改写只产生了很轻微的表达变化，没有足够价值，建议保留原文。", modelName);
        }

        String reviewCode = factCheck.passed() ? null : factCheck.code().name();
        String reviewMessage = factCheck.passed() ? null : reviewMessage(factCheck.code());
        return WorkspaceBulletSuggestionVO.builder()
                .requestId(request.getRequestId())
                .state(WorkspaceBulletSuggestionVO.STATE_READY)
                .baseRevision(currentRevision)
                .bulletId(request.getBulletId())
                .originalText(persistedOriginal)
                .suggestedText(output.suggestedText())
                .reason(output.reason())
                .reviewCode(reviewCode)
                .reviewMessage(reviewMessage)
                .modelName(modelName)
                .build();
    }

    private WorkspaceBulletSuggestionVO rejected(
            WorkspaceBulletSuggestRequestDTO request,
            long baseRevision,
            String persistedOriginal,
            String rejectCode,
            String rejectMessage,
            String modelName) {
        return WorkspaceBulletSuggestionVO.builder()
                .requestId(request.getRequestId())
                .state(WorkspaceBulletSuggestionVO.STATE_REJECTED)
                .baseRevision(baseRevision)
                .bulletId(request.getBulletId())
                .originalText(persistedOriginal)
                .rejectCode(rejectCode)
                .rejectMessage(rejectMessage)
                .modelName(modelName)
                .build();
    }

    private String reviewMessage(com.winter.airesumeoptimizer.module.workspace.enums.RewriteFactViolationCode code) {
        return switch (code) {
            case NEW_TECHNOLOGY, NEW_ENTITY -> "建议包含原文未写明的信息，请确认这些内容确实属于你的真实经历。";
            case NEW_QUANTITATIVE_CLAIM -> "建议包含新的数字或量化描述，请确认数值真实准确。";
            case RESPONSIBILITY_ESCALATION -> "建议提高了职责或参与程度的表述，请确认符合实际情况。";
            case NEW_ACHIEVEMENT -> "建议加入了新的成果或效果描述，请确认内容真实。";
            case NEW_SCOPE_OR_TIME -> "建议加入或改变了时间、范围等信息，请确认准确。";
            case UNDETERMINED -> "这次改写变化较大，系统无法自动确认所有信息，请核对后再采纳。";
            default -> null;
        };
    }

    /** 只按用户明确选中的 ID 精确查找，不做任何模糊 / 内容匹配。 */
    private ResumeDocumentBulletDTO findBullet(ResumeDocumentDTO document, String bulletId) {
        if (document == null || document.getSections() == null) {
            return null;
        }
        for (ResumeDocumentSectionDTO section : document.getSections()) {
            if (section.getEntries() == null) {
                continue;
            }
            for (ResumeDocumentEntryDTO entry : section.getEntries()) {
                if (entry.getBullets() == null) {
                    continue;
                }
                for (ResumeDocumentBulletDTO bullet : entry.getBullets()) {
                    if (bulletId.equals(bullet.getId())) {
                        return bullet;
                    }
                }
            }
        }
        return null;
    }

    private void validateRequest(WorkspaceBulletSuggestRequestDTO request) {
        if (request == null) {
            throw new BusinessException(400, "缺少改写建议请求");
        }
        if (!REQUEST_ID_PATTERN.matcher(request.getRequestId()).matches()) {
            throw new BusinessException(400, "请求 ID 格式不正确");
        }
        if (request.getIntent() == BulletSuggestIntent.CUSTOM
                && (request.getUserInstruction() == null || request.getUserInstruction().isBlank())) {
            throw new BusinessException(400, "自定义改写必须填写本次要求");
        }
    }

    private BusinessException stale(String message) {
        return new BusinessException(409, message);
    }

    private String sha256Hex(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new BusinessException(500, "内容校验失败");
        }
    }
}
