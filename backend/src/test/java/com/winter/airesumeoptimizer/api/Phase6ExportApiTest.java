package com.winter.airesumeoptimizer.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.winter.airesumeoptimizer.common.exception.BusinessException;
import com.winter.airesumeoptimizer.common.exception.GlobalExceptionHandler;
import com.winter.airesumeoptimizer.common.logging.RequestIdFilter;
import com.winter.airesumeoptimizer.config.OpenApiConfig;
import com.winter.airesumeoptimizer.config.SecurityConfig;
import com.winter.airesumeoptimizer.infra.render.ResumeTemplateId;
import com.winter.airesumeoptimizer.module.export.controller.WorkspaceExportController;
import com.winter.airesumeoptimizer.module.export.dto.WorkspaceExportRequestDTO;
import com.winter.airesumeoptimizer.module.export.service.ArtifactDownload;
import com.winter.airesumeoptimizer.module.export.service.ExportPreflight;
import com.winter.airesumeoptimizer.module.export.service.RenderedPdf;
import com.winter.airesumeoptimizer.module.export.service.WorkspaceExportService;
import com.winter.airesumeoptimizer.module.export.vo.ExportArtifactVO;
import com.winter.airesumeoptimizer.security.JwtAccessDeniedHandler;
import com.winter.airesumeoptimizer.security.JwtAuthenticationEntryPoint;
import com.winter.airesumeoptimizer.security.JwtAuthenticationFilter;
import com.winter.airesumeoptimizer.security.JwtTokenProvider;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Phase 6 Preview / Export 端点安全与协议验证：全部端点必须经过 JWT 认证，
 * 二进制响应不泄露存储路径，revision 冲突按业务错误返回而不是静默渲染。
 */
@WebMvcTest(controllers = WorkspaceExportController.class)
@Import({
        SecurityConfig.class,
        JwtAuthenticationFilter.class,
        JwtAuthenticationEntryPoint.class,
        JwtAccessDeniedHandler.class,
        GlobalExceptionHandler.class,
        RequestIdFilter.class,
        OpenApiConfig.class
})
@ActiveProfiles("test")
class Phase6ExportApiTest {

    private static final String TOKEN = "valid-token";
    private static final String AUTHORIZATION = "Bearer " + TOKEN;
    private static final byte[] PDF = "%PDF-1.7 phase6".getBytes();

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private WorkspaceExportService workspaceExportService;

    @MockitoBean
    private JwtTokenProvider jwtTokenProvider;

    @BeforeEach
    void setUpToken() {
        when(jwtTokenProvider.validateToken(TOKEN)).thenReturn(true);
        when(jwtTokenProvider.getUserId(TOKEN)).thenReturn(1L);
        when(jwtTokenProvider.getUsername(TOKEN)).thenReturn("winter");
    }

    @Test
    void previewShouldReturnPdfWithRevisionHeaderForOwner() throws Exception {
        when(workspaceExportService.preview(eq(1L), eq(42L), eq("classic"), eq(3L)))
                .thenReturn(new RenderedPdf(
                        PDF,
                        3L,
                        99L,
                        ResumeTemplateId.CLASSIC,
                        new ExportPreflight(2, false, false, false, false, false, List.of()),
                        "signed-preview-receipt"));

        mockMvc.perform(get("/api/workspace/42/preview.pdf")
                        .param("expectedRevision", "3")
                        .header("Authorization", AUTHORIZATION))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_PDF))
                .andExpect(header().string("X-Content-Revision", "3"))
                .andExpect(header().string("X-Target-Resume-Version", "99"))
                .andExpect(header().string("X-Template-Version", "5"))
                .andExpect(header().string("X-Renderer-Version", "typst-resume-renderer/3"))
                .andExpect(header().string("X-Resume-Page-Count", "2"))
                .andExpect(header().string("X-Resume-Missing-Contact", "false"))
                .andExpect(header().string("X-Resume-Overflow-Detected", "false"))
                .andExpect(header().string("X-Preview-Receipt", "signed-preview-receipt"))
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(content().bytes(PDF));
    }

    @Test
    void previewShouldRejectStaleRevisionAsConflict() throws Exception {
        when(workspaceExportService.preview(anyLong(), anyLong(), any(), anyLong()))
                .thenThrow(new BusinessException(409, "简历内容已更新，预览已失效，请刷新后重试"));

        mockMvc.perform(get("/api/workspace/42/preview.pdf")
                        .param("expectedRevision", "1")
                        .header("Authorization", AUTHORIZATION))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(409))
                .andExpect(jsonPath("$.message").value("简历内容已更新，预览已失效，请刷新后重试"));
    }

    @Test
    void previewShouldRejectAnonymousAccess() throws Exception {
        mockMvc.perform(get("/api/workspace/42/preview.pdf").param("expectedRevision", "3"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(401));
        verify(workspaceExportService, never()).preview(anyLong(), anyLong(), any(), anyLong());
    }

    @Test
    void exportShouldReturnArtifactWithoutStorageDetails() throws Exception {
        when(workspaceExportService.export(eq(1L), eq(42L), any(WorkspaceExportRequestDTO.class)))
                .thenReturn(ExportArtifactVO.builder()
                        .id(11L)
                        .optimizationTaskId(42L)
                        .templateId("classic")
                        .templateVersion("5")
                        .rendererVersion("typst-resume-renderer/3")
                        .contentRevision(3L)
                        .mimeType("application/pdf")
                        .fileSize(1234L)
                        .checksum("a".repeat(64))
                        .status("READY")
                        .pageCount(2)
                        .missingContact(false)
                        .pageLimitExceeded(false)
                        .overflowDetected(false)
                        .fileName("张三-classic.pdf")
                        .createdAt(LocalDateTime.now())
                        .build());

        WorkspaceExportRequestDTO request = new WorkspaceExportRequestDTO();
        request.setTemplateId("classic");
        request.setExpectedRevision(3L);
        request.setPreviewReceipt("signed-preview-receipt");

        mockMvc.perform(post("/api/workspace/42/export")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request))
                        .header("Authorization", AUTHORIZATION))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.id").value(11))
                .andExpect(jsonPath("$.data.contentRevision").value(3))
                .andExpect(jsonPath("$.data.templateVersion").value("5"))
                .andExpect(jsonPath("$.data.status").value("READY"))
                .andExpect(jsonPath("$.data.pageCount").value(2))
                .andExpect(jsonPath("$.data.missingContact").value(false))
                // 内部存储位置不得进入响应。
                .andExpect(jsonPath("$.data.storageKey").doesNotExist());
    }

    @Test
    void exportShouldRequireExpectedRevision() throws Exception {
        mockMvc.perform(post("/api/workspace/42/export")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"templateId\":\"classic\"}")
                        .header("Authorization", AUTHORIZATION))
                .andExpect(jsonPath("$.code").value(400));
        verify(workspaceExportService, never()).export(anyLong(), anyLong(), any());
    }

    @Test
    void exportShouldRequirePreviewReceipt() throws Exception {
        mockMvc.perform(post("/api/workspace/42/export")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"templateId\":\"classic\",\"expectedRevision\":3}")
                        .header("Authorization", AUTHORIZATION))
                .andExpect(jsonPath("$.code").value(400));
        verify(workspaceExportService, never()).export(anyLong(), anyLong(), any());
    }

    @Test
    void listArtifactsShouldRequireAuthentication() throws Exception {
        mockMvc.perform(get("/api/workspace/42/artifacts"))
                .andExpect(status().isUnauthorized());
        verify(workspaceExportService, never()).listArtifacts(anyLong(), anyLong());
    }

    @Test
    void listArtifactsShouldReturnOnlyOwnedArtifacts() throws Exception {
        when(workspaceExportService.listArtifacts(1L, 42L)).thenReturn(List.of(
                ExportArtifactVO.builder()
                        .id(11L)
                        .optimizationTaskId(42L)
                        .templateId("minimal")
                        .templateVersion("3")
                        .rendererVersion("typst-resume-renderer/3")
                        .contentRevision(3L)
                        .mimeType("application/pdf")
                        .fileSize(999L)
                        .checksum("b".repeat(64))
                        .status("READY")
                        .pageCount(1)
                        .missingContact(false)
                        .pageLimitExceeded(false)
                        .overflowDetected(false)
                        .fileName("resume-minimal-11.pdf")
                        .createdAt(LocalDateTime.now())
                        .build()));

        mockMvc.perform(get("/api/workspace/42/artifacts")
                        .header("Authorization", AUTHORIZATION))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data[0].id").value(11))
                .andExpect(jsonPath("$.data[0].storageKey").doesNotExist());
    }

    @Test
    void downloadShouldReturnAttachmentAndRejectAnonymousAccess() throws Exception {
        when(workspaceExportService.loadArtifact(1L, 11L))
                .thenReturn(new ArtifactDownload(PDF, "张三-classic.pdf", "application/pdf"));

        mockMvc.perform(get("/api/workspace/artifacts/11/download")
                        .header("Authorization", AUTHORIZATION))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_PDF))
                .andExpect(header().string("Content-Disposition",
                        org.hamcrest.Matchers.containsString("attachment")))
                .andExpect(content().bytes(PDF));

        mockMvc.perform(get("/api/workspace/artifacts/11/download"))
                .andExpect(status().isUnauthorized());
        verify(workspaceExportService, org.mockito.Mockito.times(1)).loadArtifact(anyLong(), anyLong());
    }

    @Test
    void deleteShouldRequireAuthenticationAndDelegate() throws Exception {
        mockMvc.perform(delete("/api/workspace/artifacts/11"))
                .andExpect(status().isUnauthorized());
        verify(workspaceExportService, never()).deleteArtifact(anyLong(), anyLong());

        mockMvc.perform(delete("/api/workspace/artifacts/11")
                        .header("Authorization", AUTHORIZATION))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
        verify(workspaceExportService).deleteArtifact(1L, 11L);
    }
}
