package com.winter.airesumeoptimizer.module.resume.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

class ResumeParsePropertiesConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withInitializer(new ConfigDataApplicationContextInitializer())
            .withUserConfiguration(TestConfiguration.class)
            .withSystemProperties("RESUME_PARSE_AI_SECTION_CLASSIFY_ENABLED=false");

    @Test
    void localEnvironmentFlagDisablesResumePreparationAiClassification() {
        contextRunner.run(context -> assertThat(context.getBean(ResumeParseProperties.class).aiSectionClassifyEnabled())
                .isFalse());
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(ResumeParseProperties.class)
    static class TestConfiguration {
    }
}
