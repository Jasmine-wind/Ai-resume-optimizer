package com.winter.airesumeoptimizer.infra.render;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class ResumeTemplateIdTest {

    @Test
    void currentClassicUsesVersionFourAndKeepsVersionThreeResource() {
        assertThat(ResumeTemplateId.CLASSIC.getTemplateVersion()).isEqualTo("4");
        assertThat(ResumeTemplateId.CLASSIC.getResourcePath())
                .isEqualTo("typst/classic/v4/main.typ");
        assertThat(Files.isRegularFile(Path.of("src/main/resources/typst/classic/v3/main.typ")))
                .isTrue();
        assertThat(Files.isRegularFile(Path.of("src/main/resources/typst/classic/v4/main.typ")))
                .isTrue();
    }

    @Test
    void otherCurrentTemplatesRemainVersionThree() {
        assertThat(ResumeTemplateId.MODERN.getTemplateVersion()).isEqualTo("3");
        assertThat(ResumeTemplateId.MINIMAL.getTemplateVersion()).isEqualTo("3");
        assertThat(ResumeTemplateId.fromValue(" classic ")).isEqualTo(ResumeTemplateId.CLASSIC);
    }
}
