package com.winter.airesumeoptimizer.infra.render;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.winter.airesumeoptimizer.module.resume.dto.ResumeStructuredContentDTO;
import com.winter.airesumeoptimizer.module.resume.fixture.ResumeFixtures;
import com.winter.airesumeoptimizer.module.resume.service.impl.ResumeCanonicalDocumentServiceImpl;
import com.winter.airesumeoptimizer.module.resume.service.impl.ResumeStructureParseServiceImpl;
import com.winter.airesumeoptimizer.module.workspace.dto.ResumeDocumentBasicsDTO;
import com.winter.airesumeoptimizer.module.workspace.dto.ResumeDocumentBulletDTO;
import com.winter.airesumeoptimizer.module.workspace.dto.ResumeDocumentContactDTO;
import com.winter.airesumeoptimizer.module.workspace.dto.ResumeDocumentDTO;
import com.winter.airesumeoptimizer.module.workspace.dto.ResumeDocumentEntryDTO;
import com.winter.airesumeoptimizer.module.workspace.dto.ResumeDocumentSectionDTO;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * 真实 Typst 编译验证：仅当本机存在 typst 可执行文件时运行（CI 已安装）；
 * 二进制缺失环境跳过而不是失败，保证渲染 seam 的单元语义仍被其它测试覆盖。
 */
class TypstResumeRendererCompileTest {

    private static boolean typstAvailable;

    @TempDir
    Path tempDir;

    private final TypstResumeRenderer renderer = new TypstResumeRenderer(
            renderProperties(), new TypstResumeSourceMapper(), new PdfLayoutInspector());

    @BeforeAll
    static void detectTypst() {
        try {
            Process process = new ProcessBuilder("typst", "--version").start();
            typstAvailable = process.waitFor(10, TimeUnit.SECONDS) && process.exitValue() == 0;
        } catch (IOException | InterruptedException exception) {
            typstAvailable = false;
        }
    }

    @ParameterizedTest
    @EnumSource(ResumeTemplateId.class)
    void everyBuiltInTemplateCompilesToReadablePdf(ResumeTemplateId template) throws IOException {
        assumeTrue(typstAvailable, "本机未安装 typst，跳过真实编译验证");

        ResumePdfRenderResult result = renderer.render(realisticDocument(), template);
        byte[] pdf = result.pdf();

        assertThat(pdf.length).isGreaterThan(2000);
        assertThat(new String(pdf, 0, 5)).isEqualTo("%PDF-");
        assertThat(result.layout().pageCount()).isGreaterThanOrEqualTo(1);
        assertThat(result.layout().overflowDetected()).isFalse();
        try (PDDocument document = Loader.loadPDF(pdf)) {
            assertThat(document.getNumberOfPages()).isEqualTo(result.layout().pageCount());
            assertThat(document.getDocumentInformation().getTitle()).isEqualTo("张三 · 简历");
        }
    }

    @Test
    void classicV5UsesEmbeddedRegularAndBoldCjkFonts() throws IOException {
        assumeTrue(typstAvailable, "本机未安装 typst，跳过真实编译验证");

        byte[] pdf = renderer().render(standardFixtureDocument(), ResumeTemplateId.CLASSIC).pdf();
        Set<String> names = embeddedFontNames(pdf);
        Set<String> cjkNames = names.stream()
                .filter(name -> name.toLowerCase(Locale.ROOT).contains("notosanscjk"))
                .collect(java.util.stream.Collectors.toSet());

        assertThat(cjkNames).isNotEmpty();
        assertThat(cjkNames).anyMatch(name -> name.toLowerCase(Locale.ROOT).contains("bold"));
        assertThat(cjkNames).anyMatch(name -> name.toLowerCase(Locale.ROOT).contains("regular")
                || !name.toLowerCase(Locale.ROOT).contains("bold"));
        assertThat(cjkNames).allSatisfy(name -> assertThat(name.toLowerCase(Locale.ROOT))
                .doesNotContain("thin", "extralight", "light"));
    }

    @ParameterizedTest
    @EnumSource(ResumeTemplateId.class)
    void standardFixtureNaturallyStaysOnOnePage(ResumeTemplateId template) throws IOException {
        assumeTrue(typstAvailable, "本机未安装 typst，跳过真实编译验证");

        ResumePdfRenderResult result = renderer().render(standardFixtureDocument(), template);

        assertThat(result.layout().pageCount()).isEqualTo(1);
        assertThat(result.layout().overflowDetected()).isFalse();
        assertThat(result.layout().finalPageContentRatio()).isGreaterThan(0.20f);
        assertThat(result.layout().minimumFontSizeInPt()).isGreaterThanOrEqualTo(9.0f);
        assertNoThinFonts(result.pdf());
    }

    @ParameterizedTest
    @EnumSource(ResumeTemplateId.class)
    void legalTwoPageFixtureHasMeaningfulSecondPage(ResumeTemplateId template) throws IOException {
        assumeTrue(typstAvailable, "本机未安装 typst，跳过真实编译验证");

        ResumePdfRenderResult result = renderer().render(twoPageFixtureDocument(), template);

        assertThat(result.layout().pageCount()).isEqualTo(2);
        assertThat(result.layout().overflowDetected()).isFalse();
        assertThat(result.layout().finalPageLineCount()).isGreaterThanOrEqualTo(3);
        assertThat(result.layout().finalPageContentRatio()).isGreaterThanOrEqualTo(0.20f);
        assertThat(result.layout().minimumFontSizeInPt()).isGreaterThanOrEqualTo(9.0f);
        assertNoThinFonts(result.pdf());
    }

    @ParameterizedTest
    @EnumSource(ResumeTemplateId.class)
    void mixedLanguageFixturePreservesEnglishFacts(ResumeTemplateId template) throws IOException {
        assumeTrue(typstAvailable, "本机未安装 typst，跳过真实编译验证");

        ResumePdfRenderResult result = renderer().render(mixedFixtureDocument(), template);
        String text = extractText(result.pdf());

        assertThat(result.layout().pageCount()).isEqualTo(1);
        assertThat(result.layout().overflowDetected()).isFalse();
        assertThat(text).contains(
                "Backend platform engineer focused on reliable services",
                "Designed Spring Boot services",
                "PostgreSQL",
                "AWS Certified Developer");
        assertNoThinFonts(result.pdf());
    }

    @Test
    void rendererPreservesExplicitSectionOrder() throws IOException {
        assumeTrue(typstAvailable, "本机未安装 typst，跳过真实编译验证");
        ResumeDocumentDTO document = summaryAndNonBulletDocument();
        List<ResumeDocumentSectionDTO> sections = document.getSections();
        document.setSections(List.of(sections.get(2), sections.get(0), sections.get(1), sections.get(3)));

        String source = new TypstResumeSourceMapper().mapToDataSource(document);
        assertThat(source.indexOf("kind: \"SKILL\"")).isLessThan(source.indexOf("kind: \"SUMMARY\""));

        String text = extractText(renderer().render(document, ResumeTemplateId.CLASSIC).pdf());
        assertThat(text.indexOf("技能")).isLessThan(text.indexOf("个人总结"));
        assertThat(text.indexOf("个人总结")).isLessThan(text.indexOf("教育经历"));
        assertThat(text.indexOf("教育经历")).isLessThan(text.indexOf("证书"));
    }

    @ParameterizedTest
    @EnumSource(ResumeTemplateId.class)
    void overlappingCanonicalSkillItemsAreNotSilentlyDeduplicated(ResumeTemplateId template) throws IOException {
        assumeTrue(typstAvailable, "本机未安装 typst，跳过真实编译验证");
        ResumeDocumentDTO document = summaryAndNonBulletDocument();
        document.getSections().get(2).getEntries().get(0).setSkillItems(List.of("Spring", "Spring Boot"));

        String text = extractText(renderer().render(document, template).pdf());

        assertThat(text).contains("Spring、Spring Boot");
    }

    @Test
    void everyTemplateRetainsTheSameCanonicalFacts() throws IOException {
        assumeTrue(typstAvailable, "本机未安装 typst，跳过真实编译验证");
        ResumeDocumentDTO document = standardFixtureDocument();
        List<String> facts = canonicalFacts(document);

        for (ResumeTemplateId template : ResumeTemplateId.values()) {
            String text = extractText(renderer().render(document, template).pdf())
                    .replaceAll("\\s+", "")
                    .toLowerCase(Locale.ROOT);
            for (String fact : facts) {
                assertThat(text).as("template=%s fact=%s", template, fact)
                        .contains(fact.replaceAll("\\s+", "").toLowerCase(Locale.ROOT));
            }
        }
    }

    @ParameterizedTest
    @EnumSource(ResumeTemplateId.class)
    void summarySkillsAndCertificatesDoNotUseBulletMarkers(ResumeTemplateId template) throws IOException {
        assumeTrue(typstAvailable, "本机未安装 typst，跳过真实编译验证");

        String text = extractText(renderer().render(summaryAndNonBulletDocument(), template).pdf());

        assertThat(text).contains("一段用于验证阅读层级的个人总结")
                .contains("Java、Spring Boot")
                .contains("AWS Certified Developer")
                .doesNotContain("•", "▪", "–");
    }

    @ParameterizedTest
    @EnumSource(ResumeTemplateId.class)
    void longStructuredFieldsCompileWithoutOverflow(ResumeTemplateId template) throws IOException {
        assumeTrue(typstAvailable, "本机未安装 typst，跳过真实编译验证");

        ResumePdfRenderResult result = renderer().render(longFieldDocument(), template);
        String text = extractText(result.pdf());
        String normalizedText = text.replaceAll("\\s+", " ");
        String compactText = normalizedText.replaceAll("\\s+", "");

        assertThat(result.layout().pageCount()).isBetween(1, 2);
        assertThat(result.layout().overflowDetected()).isFalse();
        assertThat(result.layout().minimumFontSizeInPt()).isGreaterThanOrEqualTo(9.0f);
        assertThat(compactText).contains(
                "DistributedCommerceandFinancialInfrastructureSystems",
                "Cross-RegionSettlementandReconciliationPlatform",
                "SanFranciscoBayArea,California");
        assertNoThinFonts(result.pdf());
    }

    @ParameterizedTest
    @EnumSource(ResumeTemplateId.class)
    void longGenericSectionsCompileWithoutOverflow(ResumeTemplateId template) throws IOException {
        assumeTrue(typstAvailable, "本机未安装 typst，跳过真实编译验证");

        ResumePdfRenderResult result = renderer().render(longGenericDocument(), template);
        String text = extractText(result.pdf());
        String compactText = text.replaceAll("\\s+", "");

        assertThat(result.layout().pageCount()).isBetween(2, 4);
        assertThat(result.layout().overflowDetected()).isFalse();
        assertThat(result.layout().finalPageLineCount()).isGreaterThanOrEqualTo(3);
        assertThat(result.layout().finalPageContentRatio()).isGreaterThanOrEqualTo(0.20f);
        assertThat(result.layout().minimumFontSizeInPt()).isGreaterThanOrEqualTo(9.0f);
        assertThat(compactText).contains(
                "LONG-SUMMARY-BEGIN", "LONG-SUMMARY-END",
                "LONG-CERTIFICATE-BEGIN", "LONG-CERTIFICATE-END",
                "LONG-ACHIEVEMENT-BEGIN", "LONG-ACHIEVEMENT-END",
                "LONG-OTHER-BEGIN", "LONG-OTHER-END");
        assertNoThinFonts(result.pdf());
    }

    @ParameterizedTest
    @EnumSource(ResumeTemplateId.class)
    void longFirstEntryBulletIsPreservedWhenItMayContinueAcrossPages(ResumeTemplateId template) throws IOException {
        assumeTrue(typstAvailable, "本机未安装 typst，跳过真实编译验证");

        ResumeDocumentDTO document = realisticDocument();
        document.getSections().get(0).getEntries().get(0).getBullets().get(0).setText(
                "LONG-FIRST-BULLET-BEGIN " + "跨页内容 ".repeat(140) + " LONG-FIRST-BULLET-END");

        ResumePdfRenderResult result = renderer().render(document, template);
        String text = extractText(result.pdf());

        assertThat(result.layout().pageCount()).isBetween(1, 2);
        assertThat(result.layout().overflowDetected()).isFalse();
        assertThat(text).contains("LONG-FIRST-BULLET-BEGIN", "LONG-FIRST-BULLET-END");
        assertThat(result.layout().minimumFontSizeInPt()).isGreaterThanOrEqualTo(9.0f);
    }

    @Test
    void renderingIsDeterministicForSameInput() {
        assumeTrue(typstAvailable, "本机未安装 typst，跳过真实编译验证");

        byte[] first = renderer.render(realisticDocument(), ResumeTemplateId.CLASSIC).pdf();
        byte[] second = renderer.render(realisticDocument(), ResumeTemplateId.CLASSIC).pdf();

        assertThat(first).isEqualTo(second);
    }

    @Test
    void injectionPayloadOnlyRendersAsLiteralText() throws IOException {
        assumeTrue(typstAvailable, "本机未安装 typst，跳过真实编译验证");

        ResumeDocumentDTO document = realisticDocument();
        document.getSections().get(0).getEntries().get(0).getBullets().add(
                ResumeDocumentBulletDTO.builder()
                        .id("b-inject")
                        .text("\" ] #import \"missing.typ\": x #read(\"/etc/passwd\") #panic(\"pwned\")")
                        .build());

        byte[] pdf = renderer.render(document, ResumeTemplateId.MINIMAL).pdf();

        // 编译成功说明注入片段没有执行任何 Typst 代码（#panic / #import 会直接编译失败）。
        assertThat(new String(pdf, 0, 5)).isEqualTo("%PDF-");
    }

    @Test
    void timeoutTerminatesCompilerAndReturnsRetryableFailure() throws IOException {
        Path slowCompiler = tempDir.resolve("slow-typst");
        Files.writeString(slowCompiler, "#!/bin/sh\nsleep 10\n");
        assertThat(slowCompiler.toFile().setExecutable(true)).isTrue();

        TypstRenderProperties slow = new TypstRenderProperties();
        slow.setTypstBinary(slowCompiler.toString());
        slow.setTimeout(Duration.ofMillis(100));
        TypstResumeRenderer slowRenderer = new TypstResumeRenderer(
                slow, new TypstResumeSourceMapper(), new PdfLayoutInspector());

        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> slowRenderer.render(realisticDocument(), ResumeTemplateId.CLASSIC))
                .isInstanceOf(ResumeRenderException.class)
                .hasMessageContaining("超时");
    }

    @Test
    void compileFailureSurfacesAsRenderException() {
        // 用一个不存在的可执行文件模拟编译器故障：必须 fail closed 且不泄露进程细节。
        TypstRenderProperties broken = new TypstRenderProperties();
        broken.setTypstBinary("/nonexistent/typst-binary");
        TypstResumeRenderer brokenRenderer = new TypstResumeRenderer(
                broken, new TypstResumeSourceMapper(), new PdfLayoutInspector());

        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> brokenRenderer.render(realisticDocument(), ResumeTemplateId.CLASSIC))
                .isInstanceOf(ResumeRenderException.class);
    }

    private TypstResumeRenderer renderer() {
        return new TypstResumeRenderer(
                renderProperties(), new TypstResumeSourceMapper(), new PdfLayoutInspector());
    }

    private TypstRenderProperties renderProperties() {
        TypstRenderProperties properties = new TypstRenderProperties();
        String configuredFontPath = System.getenv("APP_RENDER_FONT_PATH");
        if (configuredFontPath != null && !configuredFontPath.isBlank()) {
            properties.setFontPath(configuredFontPath);
        }
        return properties;
    }

    private ResumeDocumentDTO standardFixtureDocument() {
        ResumeStructuredContentDTO structured = new ResumeStructureParseServiceImpl()
                .parse(String.join("\n", ResumeFixtures.standardLines()));
        return new ResumeCanonicalDocumentServiceImpl(new ObjectMapper()).build(structured).document();
    }

    private ResumeDocumentDTO twoPageFixtureDocument() {
        ResumeStructuredContentDTO structured = new ResumeStructureParseServiceImpl()
                .parse(String.join("\n", ResumeFixtures.twoPageLines()));
        return new ResumeCanonicalDocumentServiceImpl(new ObjectMapper()).build(structured).document();
    }

    private ResumeDocumentDTO mixedFixtureDocument() {
        ResumeStructuredContentDTO structured = new ResumeStructureParseServiceImpl()
                .parse(String.join("\n", ResumeFixtures.mixedLines()));
        return new ResumeCanonicalDocumentServiceImpl(new ObjectMapper()).build(structured).document();
    }

    private List<String> canonicalFacts(ResumeDocumentDTO document) {
        List<String> facts = new ArrayList<>();
        if (document.getBasics() != null) {
            facts.add(document.getBasics().getName());
            facts.add(document.getBasics().getJobIntention());
            facts.add(document.getBasics().getHighestEducation());
            if (document.getBasics().getContacts() != null) {
                document.getBasics().getContacts().forEach(contact -> {
                    if (contact != null) {
                        facts.add(contact.getValue());
                    }
                });
            }
        }
        if (document.getSections() != null) {
            document.getSections().forEach(section -> {
                if (section == null) {
                    return;
                }
                facts.add(section.getTitle());
                if (section.getEntries() == null) {
                    return;
                }
                section.getEntries().forEach(entry -> {
                    if (entry == null) {
                        return;
                    }
                    facts.add(entry.getOrganization());
                    facts.add(entry.getRole());
                    facts.add(entry.getSchool());
                    facts.add(entry.getDegree());
                    facts.add(entry.getMajor());
                    facts.add(entry.getStartDate());
                    facts.add(entry.getEndDate());
                    facts.add(entry.getLocation());
                    facts.add(entry.getGroup());
                    if (entry.getSkillItems() != null) {
                        facts.addAll(entry.getSkillItems());
                    }
                    if (entry.getBullets() != null) {
                        entry.getBullets().forEach(bullet -> {
                            if (bullet != null) {
                                facts.add(bullet.getText());
                            }
                        });
                    }
                });
            });
        }
        return facts.stream()
                .filter(value -> value != null && !value.isBlank())
                .toList();
    }

    private ResumeDocumentDTO summaryAndNonBulletDocument() {
        return ResumeDocumentDTO.builder()
                .schemaVersion(ResumeDocumentDTO.SCHEMA_VERSION)
                .basics(ResumeDocumentBasicsDTO.builder()
                        .name("李明")
                        .contacts(List.of(contact("PHONE", "电话", "13800000000")))
                        .build())
                .sections(List.of(
                        ResumeDocumentSectionDTO.builder()
                                .kind("SUMMARY").title("个人总结")
                                .entries(List.of(ResumeDocumentEntryDTO.builder()
                                        .bullets(List.of(ResumeDocumentBulletDTO.builder()
                                                .text("一段用于验证阅读层级的个人总结").build()))
                                        .build()))
                                .build(),
                        ResumeDocumentSectionDTO.builder()
                                .kind("EDUCATION").title("教育经历")
                                .entries(List.of(ResumeDocumentEntryDTO.builder()
                                        .school("某大学")
                                        .degree("本科")
                                        .bullets(List.of(ResumeDocumentBulletDTO.builder()
                                                .text("参与校企联合实验室项目").build()))
                                        .build()))
                                .build(),
                        ResumeDocumentSectionDTO.builder()
                                .kind("SKILL").title("技能")
                                .entries(List.of(ResumeDocumentEntryDTO.builder()
                                        .group("后端技术")
                                        .skillItems(List.of("Java", "Spring Boot"))
                                        .bullets(List.of()).build()))
                                .build(),
                        ResumeDocumentSectionDTO.builder()
                                .kind("CERTIFICATE").title("证书")
                                .entries(List.of(ResumeDocumentEntryDTO.builder()
                                        .bullets(List.of(ResumeDocumentBulletDTO.builder()
                                                .text("AWS Certified Developer").build()))
                                        .build()))
                                .build()))
                .build();
    }

    private ResumeDocumentDTO longFieldDocument() {
        return ResumeDocumentDTO.builder()
                .schemaVersion(ResumeDocumentDTO.SCHEMA_VERSION)
                .basics(ResumeDocumentBasicsDTO.builder()
                        .name("A Very Long Name / 超长姓名测试")
                        .jobIntention("Principal Backend Platform Engineer")
                        .highestEducation("Master of Science in Computer Science")
                        .contacts(List.of(
                                contact("EMAIL", "邮箱", "principal.backend.engineer@example-company.com"),
                                contact("PHONE", "电话", "+1 415 555 0138"),
                                contact("WEBSITE", "个人网站", "https://www.example-company.com/people/principal-backend-engineer")))
                        .build())
                .sections(List.of(
                        structuredSection("SUMMARY", "Summary", entryWithBullets(
                                "Backend platform engineer with a long mixed-language summary that should wrap without destroying the reading rhythm.")),
                        structuredSection("EXPERIENCE", "Experience", ResumeDocumentEntryDTO.builder()
                                .organization("Very Long Company Name for Distributed Commerce and Financial Infrastructure Systems")
                                .role("Principal Backend Platform Engineer for Global Services")
                                .startDate("2022.04 (Q2)").endDate("Present / Current")
                                .location("San Francisco Bay Area, California · Remote / Hybrid")
                                .bullets(List.of(
                                        ResumeDocumentBulletDTO.builder().text("Designed a Spring Boot platform serving 1.2M requests/day while keeping p99 latency below 180ms across multiple regions.").build(),
                                        ResumeDocumentBulletDTO.builder().text("Led platform migration, observability, and incident response for teams working across North America and Asia.").build()))
                                .build()),
                        structuredSection("PROJECT", "Projects", ResumeDocumentEntryDTO.builder()
                                .organization("Extremely Long Project Name for Cross-Region Settlement and Reconciliation Platform")
                                .role("Technical Lead")
                                .startDate("2020.01 (Q1)").endDate("2022.03 (Q1)")
                                .location("Remote / New York, NY · Global")
                                .bullets(List.of(ResumeDocumentBulletDTO.builder()
                                        .text("Built a recoverable reconciliation workflow with Kafka, PostgreSQL, and Redis for a large internal developer platform.").build()))
                                .build()),
                        structuredSection("EDUCATION", "Education", ResumeDocumentEntryDTO.builder()
                                .school("University of California, San Diego")
                                .degree("M.S.").major("Computer Science and Engineering")
                                .startDate("2017.09 (Fall)").endDate("2019.06 (Summer)")
                                .location("La Jolla, California · United States").bullets(List.of()).build()),
                        ResumeDocumentSectionDTO.builder().kind("SKILL").title("Skills")
                                .entries(List.of(ResumeDocumentEntryDTO.builder()
                                        .group("Languages and Platforms")
                                        .skillItems(List.of("Java", "Kotlin", "Python", "SQL", "PostgreSQL", "Spring Boot", "Distributed Systems"))
                                        .bullets(List.of()).build()))
                                .build(),
                        structuredSection("OTHER", "Certifications", entryWithBullets("AWS Certified Developer – Associate"))))
                .build();
    }

    private ResumeDocumentDTO longGenericDocument() {
        String longSummary = "LONG-SUMMARY-BEGIN " + "这是一段需要自然跨页的总结内容，用于验证通用章节不会被不可分页容器截断。 ".repeat(100)
                + "LONG-SUMMARY-END";
        String longCertificate = "LONG-CERTIFICATE-BEGIN " + "Certificate evidence remains source-backed and readable. ".repeat(80)
                + "LONG-CERTIFICATE-END";
        String longOther = "LONG-OTHER-BEGIN " + "Other section content must wrap naturally instead of forcing a tiny or overflowing page. ".repeat(80)
                + "LONG-OTHER-END";
        return ResumeDocumentDTO.builder()
                .schemaVersion(ResumeDocumentDTO.SCHEMA_VERSION)
                .basics(ResumeDocumentBasicsDTO.builder().name("李明").build())
                .sections(List.of(
                        structuredSection("SUMMARY", "个人总结", entryWithBullets(longSummary)),
                        structuredSection("CERTIFICATE", "证书", entryWithBullets(longCertificate)),
                        structuredSection("ACHIEVEMENT", "成就", entryWithBullets(
                                "LONG-ACHIEVEMENT-BEGIN " + "Achievement content remains readable across pages. ".repeat(60)
                                        + "LONG-ACHIEVEMENT-END")),
                        structuredSection("OTHER", "其他", entryWithBullets(longOther))))
                .build();
    }

    private ResumeDocumentSectionDTO structuredSection(
            String kind, String title, ResumeDocumentEntryDTO entry) {
        return ResumeDocumentSectionDTO.builder().kind(kind).title(title).entries(List.of(entry)).build();
    }

    private ResumeDocumentEntryDTO entryWithBullets(String... bullets) {
        return ResumeDocumentEntryDTO.builder()
                .bullets(Stream.of(bullets)
                        .map(text -> ResumeDocumentBulletDTO.builder().text(text).build())
                        .toList())
                .build();
    }

    private ResumeDocumentContactDTO contact(String type, String label, String value) {
        return ResumeDocumentContactDTO.builder().type(type).label(label).value(value).build();
    }

    private String extractText(byte[] pdf) throws IOException {
        try (PDDocument document = Loader.loadPDF(pdf)) {
            return new PDFTextStripper().getText(document);
        }
    }

    private Set<String> embeddedFontNames(byte[] pdf) throws IOException {
        Set<String> names = new HashSet<>();
        try (PDDocument document = Loader.loadPDF(pdf)) {
            for (PDPage page : document.getPages()) {
                for (COSName fontName : page.getResources().getFontNames()) {
                    PDFont font = page.getResources().getFont(fontName);
                    names.add(font.getName());
                    if (font.getFontDescriptor() != null) {
                        names.add(font.getFontDescriptor().getFontName());
                    }
                }
            }
        }
        return names;
    }

    private void assertNoThinFonts(byte[] pdf) throws IOException {
        for (String name : embeddedFontNames(pdf)) {
            assertThat(name).doesNotContainIgnoringCase("thin", "extralight", "light");
        }
    }

    private ResumeDocumentDTO realisticDocument() {
        return ResumeDocumentDTO.builder()
                .schemaVersion(ResumeDocumentDTO.SCHEMA_VERSION)
                .basics(ResumeDocumentBasicsDTO.builder()
                        .name("张三")
                        .contacts(Stream.of(
                                        contact("c-1", "PHONE", "电话", "13800000000"),
                                        contact("c-2", "EMAIL", "邮箱", "zhangsan@example.com"),
                                        contact("c-3", "LOCATION", "所在地", "北京"))
                                .toList())
                        .build())
                .sections(List.of(
                        ResumeDocumentSectionDTO.builder()
                                .id("s-1")
                                .kind("EXPERIENCE")
                                .title("工作经历")
                                .entries(List.of(ResumeDocumentEntryDTO.builder()
                                        .id("e-1")
                                        .organization("某科技有限公司")
                                        .role("Java 后端")
                                        .startDate("2022.07")
                                        .endDate("至今")
                                        .bullets(new java.util.ArrayList<>(List.of(
                                                ResumeDocumentBulletDTO.builder()
                                                        .id("b-1")
                                                        .text("负责核心交易链路开发，日均处理订单 100 万笔")
                                                        .build(),
                                                ResumeDocumentBulletDTO.builder()
                                                        .id("b-2")
                                                        .text("基于 Spring Boot 与 Redis 完成缓存设计")
                                                        .build())))
                                        .build()))
                                .build(),
                        ResumeDocumentSectionDTO.builder()
                                .id("s-2")
                                .kind("SKILL")
                                .title("技能")
                                .entries(List.of(ResumeDocumentEntryDTO.builder()
                                        .id("e-2")
                                        .group("后端技术")
                                        .skillItems(new java.util.ArrayList<>(
                                                List.of("Java", "Spring Boot", "MySQL", "Redis")))
                                        .bullets(new java.util.ArrayList<>())
                                        .build()))
                                .build()))
                .build();
    }

    private ResumeDocumentContactDTO contact(String id, String type, String label, String value) {
        return ResumeDocumentContactDTO.builder().id(id).type(type).label(label).value(value).build();
    }
}
