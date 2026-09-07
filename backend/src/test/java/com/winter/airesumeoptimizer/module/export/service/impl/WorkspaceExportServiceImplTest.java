package com.winter.airesumeoptimizer.module.export.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.winter.airesumeoptimizer.common.exception.BusinessException;
import com.winter.airesumeoptimizer.infra.render.PdfLayoutInspection;
import com.winter.airesumeoptimizer.infra.render.ResumePdfRenderer;
import com.winter.airesumeoptimizer.infra.render.ResumePdfRenderResult;
import com.winter.airesumeoptimizer.infra.render.ResumeRenderException;
import com.winter.airesumeoptimizer.infra.render.ResumeTemplateId;
import com.winter.airesumeoptimizer.infra.storage.FileStorageException;
import com.winter.airesumeoptimizer.infra.storage.FileStorageService;
import com.winter.airesumeoptimizer.infra.storage.StoreFileCommand;
import com.winter.airesumeoptimizer.infra.storage.StoredFile;
import com.winter.airesumeoptimizer.module.export.dto.WorkspaceExportRequestDTO;
import com.winter.airesumeoptimizer.module.export.entity.ExportArtifact;
import com.winter.airesumeoptimizer.module.export.mapper.ExportArtifactMapper;
import com.winter.airesumeoptimizer.module.export.service.ArtifactDownload;
import com.winter.airesumeoptimizer.module.export.service.ExportArtifactCleanupService;
import com.winter.airesumeoptimizer.module.export.service.ExportDocumentGate;
import com.winter.airesumeoptimizer.module.export.service.ExportPreflight;
import com.winter.airesumeoptimizer.module.export.service.ExportPreflightChecker;
import com.winter.airesumeoptimizer.module.export.service.PreviewReceiptClaims;
import com.winter.airesumeoptimizer.module.export.service.RenderedPdf;
import com.winter.airesumeoptimizer.module.export.vo.ExportArtifactVO;
import com.winter.airesumeoptimizer.module.optimization.entity.OptimizationTask;
import com.winter.airesumeoptimizer.module.optimization.mapper.OptimizationTaskMapper;
import com.winter.airesumeoptimizer.module.workspace.dto.ResumeDocumentBasicsDTO;
import com.winter.airesumeoptimizer.module.workspace.dto.ResumeDocumentDTO;
import com.winter.airesumeoptimizer.module.workspace.service.WorkspaceContentService;
import com.winter.airesumeoptimizer.module.workspace.vo.WorkspaceContentVO;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.function.Consumer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Phase 6 导出编排基线：数据源只来自 Workspace 已保存内容；revision 不一致拒绝；
 * 存储/数据库失败不得产生半完成记录，数据库失败必须补偿删除存储对象。
 */
@ExtendWith(MockitoExtension.class)
class WorkspaceExportServiceImplTest {

    private static final Long USER_ID = 7L;
    private static final Long TASK_ID = 42L;
    private static final Long TARGET_VERSION_ID = 99L;
    private static final long REVISION = 3L;
    private static final byte[] PDF = "%PDF-1.7 fake-bytes".getBytes();
    private static final String PREVIEW_RECEIPT = "signed-preview-receipt";

    @Mock
    private WorkspaceContentService workspaceContentService;
    @Mock
    private ResumePdfRenderer resumePdfRenderer;
    @Mock
    private FileStorageService fileStorageService;
    @Mock
    private ExportArtifactMapper exportArtifactMapper;
    @Mock
    private OptimizationTaskMapper optimizationTaskMapper;
    @Mock
    private ExportPreflightChecker preflightChecker;
    @Mock
    private ExportDocumentGate exportDocumentGate;
    @Mock
    private PreviewReceiptService previewReceiptService;
    @Mock
    private ExportArtifactCleanupService exportArtifactCleanupService;
    @Mock
    private TransactionTemplate transactionTemplate;

    private WorkspaceExportServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new WorkspaceExportServiceImpl(
                workspaceContentService, resumePdfRenderer, fileStorageService,
                exportArtifactMapper, optimizationTaskMapper, preflightChecker, exportDocumentGate,
                previewReceiptService, exportArtifactCleanupService, transactionTemplate);
        // 默认放行文档质量门；阻断场景在用例内覆盖打桩。
        lenient().when(exportDocumentGate.check(any(), any(), any()))
                .thenReturn(new ExportDocumentGate.GateResult(
                        ExportDocumentGate.STATUS_PASS, null, "READY", false));
    }

    private WorkspaceContentVO savedContent() {
        return WorkspaceContentVO.builder()
                .optimizationTaskId(TASK_ID)
                .revision(REVISION)
                .document(ResumeDocumentDTO.builder()
                        .schemaVersion(ResumeDocumentDTO.SCHEMA_VERSION)
                        .basics(ResumeDocumentBasicsDTO.builder().name("张三").build())
                        .sections(List.of())
                        .build())
                .build();
    }

    private void givenTaskWithTargetVersion() {
        OptimizationTask task = new OptimizationTask();
        task.setId(TASK_ID);
        task.setUserId(USER_ID);
        task.setTargetResumeVersionId(TARGET_VERSION_ID);
        when(optimizationTaskMapper.selectOne(any())).thenReturn(task);
    }

    private ResumePdfRenderResult renderResult() {
        return new ResumePdfRenderResult(PDF, new PdfLayoutInspection(2, false));
    }

    private ExportPreflight preflight() {
        return new ExportPreflight(2, false, false, false, false, false, List.of());
    }

    private PreviewReceiptClaims receiptClaims(String templateId) {
        return new PreviewReceiptClaims(
                USER_ID, TASK_ID, TARGET_VERSION_ID, REVISION,
                templateId, ResumeTemplateId.fromValue(templateId).getTemplateVersion(),
                ResumePdfRenderer.RENDERER_VERSION, sha256(PDF));
    }

    private void givenValidReceipt(String templateId) {
        when(previewReceiptService.verify(PREVIEW_RECEIPT)).thenReturn(receiptClaims(templateId));
        when(preflightChecker.check(any(), any(), anyBoolean())).thenReturn(preflight());
    }

    private String sha256(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    private void givenCommittedInsert() {
        doAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            Consumer<TransactionStatus> action = invocation.getArgument(0);
            action.accept(null);
            return null;
        }).when(transactionTemplate).executeWithoutResult(any());
        when(exportArtifactMapper.insert(any(ExportArtifact.class))).thenReturn(1);
    }

    @Test
    void previewRendersOnlySavedServerContent() {
        when(workspaceContentService.getPersistedContentForRender(USER_ID, TASK_ID)).thenReturn(savedContent());
        when(resumePdfRenderer.render(any(ResumeDocumentDTO.class), any(ResumeTemplateId.class)))
                .thenReturn(renderResult());
        givenTaskWithTargetVersion();
        when(preflightChecker.check(any(), any(), anyBoolean())).thenReturn(preflight());
        when(previewReceiptService.issue(any())).thenReturn(PREVIEW_RECEIPT);

        RenderedPdf rendered = service.preview(USER_ID, TASK_ID, "classic", REVISION);

        assertThat(rendered.pdf()).isEqualTo(PDF);
        assertThat(rendered.revision()).isEqualTo(REVISION);
        assertThat(rendered.template()).isEqualTo(ResumeTemplateId.CLASSIC);
        assertThat(rendered.targetResumeVersionId()).isEqualTo(TARGET_VERSION_ID);
        assertThat(rendered.preflight()).isEqualTo(preflight());
        assertThat(rendered.previewReceipt()).isEqualTo(PREVIEW_RECEIPT);
    }

    @Test
    void previewRejectsStaleRevision() {
        when(workspaceContentService.getPersistedContentForRender(USER_ID, TASK_ID)).thenReturn(savedContent());

        assertThatThrownBy(() -> service.preview(USER_ID, TASK_ID, "classic", REVISION - 1))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getCode()).isEqualTo(409));
        verify(resumePdfRenderer, never()).render(any(), any());
    }

    @Test
    void previewRejectsUnknownTemplate() {
        assertThatThrownBy(() -> service.preview(USER_ID, TASK_ID, "ats-beater", REVISION))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getCode()).isEqualTo(400));
        verify(workspaceContentService, never()).getPersistedContentForRender(anyLong(), anyLong());
    }

    @Test
    void previewAllowsNeedsReviewContentAndSurfacesReviewFlag() {
        // 待确认内容仍允许预览（审查工具），但 preflight 携带待确认标志；正式导出的阻断在 export 路径。
        when(workspaceContentService.getPersistedContentForRender(USER_ID, TASK_ID)).thenReturn(savedContent());
        when(resumePdfRenderer.render(any(ResumeDocumentDTO.class), any(ResumeTemplateId.class)))
                .thenReturn(renderResult());
        givenTaskWithTargetVersion();
        when(exportDocumentGate.check(any(), any(), any()))
                .thenReturn(new ExportDocumentGate.GateResult(
                        ExportDocumentGate.STATUS_BLOCK,
                        ExportDocumentGate.CODE_DOCUMENT_NOT_CONFIRMED,
                        "NEEDS_REVIEW",
                        true));
        ExportPreflight reviewPreflight = new ExportPreflight(2, false, false, false, false, true, List.of());
        when(preflightChecker.check(any(), any(), anyBoolean())).thenReturn(reviewPreflight);
        when(previewReceiptService.issue(any())).thenReturn(PREVIEW_RECEIPT);

        RenderedPdf rendered = service.preview(USER_ID, TASK_ID, "classic", REVISION);

        assertThat(rendered.preflight().needsReview()).isTrue();
        assertThat(rendered.previewReceipt()).isEqualTo(PREVIEW_RECEIPT);
    }

    @Test
    void previewRejectsUnrenderableQualityBeforeRender() {
        when(workspaceContentService.getPersistedContentForRender(USER_ID, TASK_ID)).thenReturn(savedContent());
        givenTaskWithTargetVersion();
        when(exportDocumentGate.check(any(), any(), any()))
                .thenReturn(new ExportDocumentGate.GateResult(
                        ExportDocumentGate.STATUS_BLOCK,
                        ExportDocumentGate.CODE_RESUME_QUALITY_FAILED,
                        "FAILED",
                        false));

        assertThatThrownBy(() -> service.preview(USER_ID, TASK_ID, "classic", REVISION))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> {
                            assertThat(exception.getCode()).isEqualTo(409);
                            assertThat(exception.getMessage()).contains("RESUME_QUALITY_FAILED");
                        });
        verify(resumePdfRenderer, never()).render(any(), any());
    }

    @Test
    void exportRejectsWhenDocumentQualityGateBlocks() {
        when(workspaceContentService.getPersistedContentForRender(USER_ID, TASK_ID)).thenReturn(savedContent());
        givenTaskWithTargetVersion();
        when(exportDocumentGate.check(any(), any(), any()))
                .thenReturn(new ExportDocumentGate.GateResult(
                        ExportDocumentGate.STATUS_BLOCK,
                        ExportDocumentGate.CODE_DOCUMENT_NOT_CONFIRMED,
                        "NEEDS_REVIEW",
                        true));

        WorkspaceExportRequestDTO request = new WorkspaceExportRequestDTO();
        request.setTemplateId("classic");
        request.setExpectedRevision(REVISION);
        request.setPreviewReceipt(PREVIEW_RECEIPT);

        assertThatThrownBy(() -> service.export(USER_ID, TASK_ID, request))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> {
                            assertThat(exception.getCode()).isEqualTo(409);
                            assertThat(exception.getMessage()).contains("DOCUMENT_NOT_CONFIRMED");
                        });
        verify(resumePdfRenderer, never()).render(any(), any());
        verify(fileStorageService, never()).store(any());
    }

    @Test
    void exportRejectsWhenPdfGateDetectsOverflowOrOrphanFinalPage() {
        when(workspaceContentService.getPersistedContentForRender(USER_ID, TASK_ID)).thenReturn(savedContent());
        givenTaskWithTargetVersion();
        when(resumePdfRenderer.render(any(), any())).thenReturn(renderResult());

        WorkspaceExportRequestDTO request = new WorkspaceExportRequestDTO();
        request.setTemplateId("classic");
        request.setExpectedRevision(REVISION);
        request.setPreviewReceipt(PREVIEW_RECEIPT);

        // 文字越界：正式导出阻断。
        when(previewReceiptService.verify(PREVIEW_RECEIPT)).thenReturn(receiptClaims("classic"));
        when(preflightChecker.check(any(), any(), anyBoolean()))
                .thenReturn(new ExportPreflight(2, false, false, true, false, false, List.of()));
        assertThatThrownBy(() -> service.export(USER_ID, TASK_ID, request))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> {
                            assertThat(exception.getCode()).isEqualTo(409);
                            assertThat(exception.getMessage()).contains("CONTENT_OUT_OF_PAGE_BOUNDS");
                        });

        // 孤立末页（页数 ≥2 且末页非空行 <3）：正式导出阻断。
        when(preflightChecker.check(any(), any(), anyBoolean()))
                .thenReturn(new ExportPreflight(2, false, false, false, true, false, List.of()));
        assertThatThrownBy(() -> service.export(USER_ID, TASK_ID, request))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> {
                            assertThat(exception.getCode()).isEqualTo(409);
                            assertThat(exception.getMessage()).contains("ORPHAN_FINAL_PAGE");
                        });
        verify(fileStorageService, never()).store(any());
    }

    @Test
    void exportRejectsUnreadableFontBeforeStorage() {
        when(workspaceContentService.getPersistedContentForRender(USER_ID, TASK_ID)).thenReturn(savedContent());
        givenTaskWithTargetVersion();
        when(resumePdfRenderer.render(any(ResumeDocumentDTO.class), any(ResumeTemplateId.class)))
                .thenReturn(renderResult());
        when(previewReceiptService.verify(PREVIEW_RECEIPT)).thenReturn(receiptClaims("classic"));
        when(preflightChecker.check(any(), any(), anyBoolean()))
                .thenReturn(new ExportPreflight(1, false, false, false, false, true, false,
                        List.of("READABILITY_TOO_SMALL")));

        WorkspaceExportRequestDTO request = new WorkspaceExportRequestDTO();
        request.setTemplateId("classic");
        request.setExpectedRevision(REVISION);
        request.setPreviewReceipt(PREVIEW_RECEIPT);

        assertThatThrownBy(() -> service.export(USER_ID, TASK_ID, request))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getMessage()).contains("READABILITY_TOO_SMALL"));
        verify(fileStorageService, never()).store(any());
    }

    @Test
    void renderFailureSurfacesAsBusinessErrorWithoutSideEffects() {
        when(workspaceContentService.getPersistedContentForRender(USER_ID, TASK_ID)).thenReturn(savedContent());
        givenTaskWithTargetVersion();
        when(resumePdfRenderer.render(any(), any()))
                .thenThrow(new ResumeRenderException("简历排版编译失败，请检查内容后重试"));

        assertThatThrownBy(() -> service.preview(USER_ID, TASK_ID, "modern", REVISION))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getCode()).isEqualTo(500));
        verify(fileStorageService, never()).store(any());
        verify(exportArtifactMapper, never()).insert(any(ExportArtifact.class));
    }

    @Test
    void exportRejectsStaleRevisionBeforeReceiptOrRender() {
        when(workspaceContentService.getPersistedContentForRender(USER_ID, TASK_ID)).thenReturn(savedContent());
        WorkspaceExportRequestDTO request = new WorkspaceExportRequestDTO();
        request.setTemplateId("classic");
        request.setExpectedRevision(REVISION - 1);
        request.setPreviewReceipt(PREVIEW_RECEIPT);

        assertThatThrownBy(() -> service.export(USER_ID, TASK_ID, request))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getCode()).isEqualTo(409));
        verify(previewReceiptService, never()).verify(any());
        verify(resumePdfRenderer, never()).render(any(), any());
    }

    @Test
    void exportRejectsMissingPreviewReceiptBeforeRender() {
        when(workspaceContentService.getPersistedContentForRender(USER_ID, TASK_ID)).thenReturn(savedContent());
        givenTaskWithTargetVersion();
        when(previewReceiptService.verify(null)).thenThrow(new IllegalArgumentException("missing"));
        WorkspaceExportRequestDTO request = new WorkspaceExportRequestDTO();
        request.setTemplateId("classic");
        request.setExpectedRevision(REVISION);

        assertThatThrownBy(() -> service.export(USER_ID, TASK_ID, request))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getCode()).isEqualTo(409));
        verify(resumePdfRenderer, never()).render(any(), any());
        verify(fileStorageService, never()).store(any());
    }

    @Test
    void exportRejectsReceiptBoundToAnotherTemplateBeforeRender() {
        when(workspaceContentService.getPersistedContentForRender(USER_ID, TASK_ID)).thenReturn(savedContent());
        givenTaskWithTargetVersion();
        when(previewReceiptService.verify(PREVIEW_RECEIPT)).thenReturn(receiptClaims("modern"));
        WorkspaceExportRequestDTO request = new WorkspaceExportRequestDTO();
        request.setTemplateId("classic");
        request.setExpectedRevision(REVISION);
        request.setPreviewReceipt(PREVIEW_RECEIPT);

        assertThatThrownBy(() -> service.export(USER_ID, TASK_ID, request))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getCode()).isEqualTo(409));
        verify(resumePdfRenderer, never()).render(any(), any());
    }

    @Test
    void exportRejectsReceiptBoundToAnotherUserTaskTargetTemplateVersionOrRenderer() {
        when(workspaceContentService.getPersistedContentForRender(USER_ID, TASK_ID)).thenReturn(savedContent());
        givenTaskWithTargetVersion();
        WorkspaceExportRequestDTO request = new WorkspaceExportRequestDTO();
        request.setTemplateId("classic");
        request.setExpectedRevision(REVISION);
        request.setPreviewReceipt(PREVIEW_RECEIPT);
        List<PreviewReceiptClaims> invalidClaims = List.of(
                new PreviewReceiptClaims(8L, TASK_ID, TARGET_VERSION_ID, REVISION,
                        "classic", "1", ResumePdfRenderer.RENDERER_VERSION, sha256(PDF)),
                new PreviewReceiptClaims(USER_ID, 43L, TARGET_VERSION_ID, REVISION,
                        "classic", "1", ResumePdfRenderer.RENDERER_VERSION, sha256(PDF)),
                new PreviewReceiptClaims(USER_ID, TASK_ID, 100L, REVISION,
                        "classic", "1", ResumePdfRenderer.RENDERER_VERSION, sha256(PDF)),
                new PreviewReceiptClaims(USER_ID, TASK_ID, TARGET_VERSION_ID, REVISION,
                        "classic", "3", ResumePdfRenderer.RENDERER_VERSION, sha256(PDF)),
                new PreviewReceiptClaims(USER_ID, TASK_ID, TARGET_VERSION_ID, REVISION,
                        "classic", "1", "another-renderer", sha256(PDF)));

        for (PreviewReceiptClaims claims : invalidClaims) {
            when(previewReceiptService.verify(PREVIEW_RECEIPT)).thenReturn(claims);
            assertThatThrownBy(() -> service.export(USER_ID, TASK_ID, request))
                    .isInstanceOfSatisfying(BusinessException.class,
                            exception -> assertThat(exception.getCode()).isEqualTo(409));
        }
        verify(resumePdfRenderer, never()).render(any(), any());
        verify(fileStorageService, never()).store(any());
    }

    @Test
    void exportRejectsWhenRecompiledPdfDiffersFromPreview() {
        when(workspaceContentService.getPersistedContentForRender(USER_ID, TASK_ID)).thenReturn(savedContent());
        givenTaskWithTargetVersion();
        PreviewReceiptClaims differentChecksum = new PreviewReceiptClaims(
                USER_ID, TASK_ID, TARGET_VERSION_ID, REVISION,
                "classic", ResumeTemplateId.CLASSIC.getTemplateVersion(),
                ResumePdfRenderer.RENDERER_VERSION, "0".repeat(64));
        when(previewReceiptService.verify(PREVIEW_RECEIPT)).thenReturn(differentChecksum);
        when(resumePdfRenderer.render(any(), any())).thenReturn(renderResult());
        WorkspaceExportRequestDTO request = new WorkspaceExportRequestDTO();
        request.setTemplateId("classic");
        request.setExpectedRevision(REVISION);
        request.setPreviewReceipt(PREVIEW_RECEIPT);

        assertThatThrownBy(() -> service.export(USER_ID, TASK_ID, request))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getCode()).isEqualTo(409));
        verify(fileStorageService, never()).store(any());
    }

    @Test
    void exportRecordsTemplateRendererRevisionAndChecksum() throws Exception {
        when(workspaceContentService.getPersistedContentForRender(USER_ID, TASK_ID)).thenReturn(savedContent());
        when(resumePdfRenderer.render(any(), any())).thenReturn(renderResult());
        givenTaskWithTargetVersion();
        givenValidReceipt("modern");
        givenCommittedInsert();
        when(fileStorageService.store(any(StoreFileCommand.class))).thenReturn(
                new StoredFile("exports/7/20260821/key.pdf", "resume.pdf", "application/pdf",
                        PDF.length, "LOCAL"));

        WorkspaceExportRequestDTO request = new WorkspaceExportRequestDTO();
        request.setTemplateId("modern");
        request.setExpectedRevision(REVISION);
        request.setPreviewReceipt(PREVIEW_RECEIPT);
        ExportArtifactVO vo = service.export(USER_ID, TASK_ID, request);

        ArgumentCaptor<ExportArtifact> captor = ArgumentCaptor.forClass(ExportArtifact.class);
        verify(exportArtifactMapper).insert(captor.capture());
        ExportArtifact stored = captor.getValue();
        assertThat(stored.getUserId()).isEqualTo(USER_ID);
        assertThat(stored.getOptimizationTaskId()).isEqualTo(TASK_ID);
        assertThat(stored.getTargetResumeVersionId()).isEqualTo(TARGET_VERSION_ID);
        assertThat(stored.getContentRevision()).isEqualTo(REVISION);
        assertThat(stored.getTemplateId()).isEqualTo("modern");
        assertThat(stored.getTemplateVersion()).isEqualTo("3");
        assertThat(stored.getRendererVersion()).isEqualTo(ResumePdfRenderer.RENDERER_VERSION);
        assertThat(stored.getFileSize()).isEqualTo((long) PDF.length);
        assertThat(stored.getStatus()).isEqualTo("READY");
        assertThat(stored.getPageCount()).isEqualTo(2);
        assertThat(stored.getMissingContact()).isFalse();
        assertThat(stored.getOverflowDetected()).isFalse();
        assertThat(stored.getChecksumSha256()).isEqualTo(HexFormat.of()
                .formatHex(MessageDigest.getInstance("SHA-256").digest(PDF)));

        assertThat(vo.getTemplateId()).isEqualTo("modern");
        assertThat(vo.getContentRevision()).isEqualTo(REVISION);
        assertThat(vo.getFileName()).contains("modern").endsWith(".pdf");
    }

    @Test
    void exportResolvesTargetBeforeCreatingStorageObject() {
        when(workspaceContentService.getPersistedContentForRender(USER_ID, TASK_ID)).thenReturn(savedContent());
        when(optimizationTaskMapper.selectOne(any())).thenThrow(new RuntimeException("database unavailable"));

        WorkspaceExportRequestDTO request = new WorkspaceExportRequestDTO();
        request.setTemplateId("classic");
        request.setExpectedRevision(REVISION);
        request.setPreviewReceipt(PREVIEW_RECEIPT);

        assertThatThrownBy(() -> service.export(USER_ID, TASK_ID, request))
                .isInstanceOf(RuntimeException.class);
        verify(resumePdfRenderer, never()).render(any(), any());
        verify(fileStorageService, never()).store(any());
    }

    @Test
    void exportWithoutStorageSuccessWritesNoRecord() {
        when(workspaceContentService.getPersistedContentForRender(USER_ID, TASK_ID)).thenReturn(savedContent());
        givenTaskWithTargetVersion();
        givenValidReceipt("classic");
        when(resumePdfRenderer.render(any(), any())).thenReturn(renderResult());
        when(fileStorageService.store(any(StoreFileCommand.class)))
                .thenThrow(new FileStorageException("文件保存失败"));

        WorkspaceExportRequestDTO request = new WorkspaceExportRequestDTO();
        request.setTemplateId("classic");
        request.setExpectedRevision(REVISION);
        request.setPreviewReceipt(PREVIEW_RECEIPT);

        assertThatThrownBy(() -> service.export(USER_ID, TASK_ID, request))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getCode()).isEqualTo(500));
        verify(exportArtifactMapper, never()).insert(any(ExportArtifact.class));
    }

    @Test
    void exportCompensatesStorageWhenDatabaseInsertFails() {
        when(workspaceContentService.getPersistedContentForRender(USER_ID, TASK_ID)).thenReturn(savedContent());
        when(resumePdfRenderer.render(any(), any())).thenReturn(renderResult());
        givenTaskWithTargetVersion();
        givenValidReceipt("classic");
        when(fileStorageService.store(any(StoreFileCommand.class))).thenReturn(
                new StoredFile("exports/7/20260821/key.pdf", "resume.pdf", "application/pdf",
                        PDF.length, "LOCAL"));
        doThrow(new RuntimeException("connection reset"))
                .when(transactionTemplate).executeWithoutResult(any());

        WorkspaceExportRequestDTO request = new WorkspaceExportRequestDTO();
        request.setTemplateId("classic");
        request.setExpectedRevision(REVISION);
        request.setPreviewReceipt(PREVIEW_RECEIPT);

        assertThatThrownBy(() -> service.export(USER_ID, TASK_ID, request))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getCode()).isEqualTo(500));
        // 数据库记录失败：必须补偿删除存储对象，不允许孤儿文件成为可下载内容。
        verify(fileStorageService).delete("exports/7/20260821/key.pdf");
    }

    @Test
    void exportCompensatesStorageWhenDatabaseInsertWritesNoRow() {
        when(workspaceContentService.getPersistedContentForRender(USER_ID, TASK_ID)).thenReturn(savedContent());
        when(resumePdfRenderer.render(any(), any())).thenReturn(renderResult());
        givenTaskWithTargetVersion();
        givenValidReceipt("classic");
        when(fileStorageService.store(any(StoreFileCommand.class))).thenReturn(
                new StoredFile("exports/7/20260821/key.pdf", "resume.pdf", "application/pdf",
                        PDF.length, "LOCAL"));
        doAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            Consumer<TransactionStatus> action = invocation.getArgument(0);
            action.accept(null);
            return null;
        }).when(transactionTemplate).executeWithoutResult(any());
        when(exportArtifactMapper.insert(any(ExportArtifact.class))).thenReturn(0);

        WorkspaceExportRequestDTO request = new WorkspaceExportRequestDTO();
        request.setTemplateId("classic");
        request.setExpectedRevision(REVISION);
        request.setPreviewReceipt(PREVIEW_RECEIPT);

        assertThatThrownBy(() -> service.export(USER_ID, TASK_ID, request))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getCode()).isEqualTo(500));
        verify(fileStorageService).delete("exports/7/20260821/key.pdf");
    }

    @Test
    void loadArtifactVerifiesChecksumAndOwnership() {
        ExportArtifact artifact = artifactRow();
        when(exportArtifactMapper.selectOne(any())).thenReturn(artifact);
        when(fileStorageService.loadAsBytes(artifact.getStorageKey())).thenReturn(PDF);

        ArtifactDownload download = service.loadArtifact(USER_ID, artifact.getId());

        assertThat(download.pdf()).isEqualTo(PDF);
        assertThat(download.fileName()).endsWith(".pdf");
    }

    @Test
    void loadArtifactRejectsTamperedContent() throws Exception {
        ExportArtifact artifact = artifactRow();
        artifact.setChecksumSha256(HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest("other-content".getBytes())));
        when(exportArtifactMapper.selectOne(any())).thenReturn(artifact);
        when(fileStorageService.loadAsBytes(artifact.getStorageKey())).thenReturn(PDF);

        assertThatThrownBy(() -> service.loadArtifact(USER_ID, artifact.getId()))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getCode()).isEqualTo(500));
    }

    @Test
    void loadArtifactRejectsDeletePendingRecord() {
        ExportArtifact artifact = artifactRow();
        artifact.setStatus("DELETE_PENDING");
        when(exportArtifactMapper.selectOne(any())).thenReturn(artifact);

        assertThatThrownBy(() -> service.loadArtifact(USER_ID, artifact.getId()))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getCode()).isEqualTo(409));
        verify(fileStorageService, never()).loadAsBytes(any());
    }

    @Test
    void loadArtifactReturns404ForForeignArtifact() {
        when(exportArtifactMapper.selectOne(any())).thenReturn(null);

        assertThatThrownBy(() -> service.loadArtifact(USER_ID, 12345L))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getCode()).isEqualTo(404));
        verify(fileStorageService, never()).loadAsBytes(any());
    }

    @Test
    void deleteArtifactDelegatesToRecoverableLifecycleSeam() {
        service.deleteArtifact(USER_ID, 11L);

        verify(exportArtifactCleanupService).deleteArtifact(USER_ID, 11L);
    }

    private ExportArtifact artifactRow() {
        ExportArtifact artifact = new ExportArtifact();
        artifact.setId(11L);
        artifact.setUserId(USER_ID);
        artifact.setOptimizationTaskId(TASK_ID);
        artifact.setTargetResumeVersionId(TARGET_VERSION_ID);
        artifact.setContentRevision(REVISION);
        artifact.setTemplateId("classic");
        artifact.setTemplateVersion("1");
        artifact.setRendererVersion(ResumePdfRenderer.RENDERER_VERSION);
        artifact.setStorageKey("exports/7/20260821/key.pdf");
        artifact.setMimeType("application/pdf");
        artifact.setFileSize((long) PDF.length);
        artifact.setStatus("READY");
        artifact.setPageCount(2);
        artifact.setMissingContact(false);
        artifact.setPageLimitExceeded(false);
        artifact.setOverflowDetected(false);
        try {
            artifact.setChecksumSha256(HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(PDF)));
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
        artifact.setCreatedAt(LocalDateTime.now());
        return artifact;
    }
}
