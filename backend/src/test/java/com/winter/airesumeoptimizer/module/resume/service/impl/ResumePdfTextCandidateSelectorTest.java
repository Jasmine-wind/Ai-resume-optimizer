package com.winter.airesumeoptimizer.module.resume.service.impl;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ResumePdfTextCandidateSelectorTest {

    private final ResumePdfTextCandidateSelector selector = new ResumePdfTextCandidateSelector();

    @Test
    void tieShouldPreferLegacy() {
        var selection = selector.select("Name\nemail@example.com", "Name\nemail@example.com");

        assertThat(selection.candidateType()).isEqualTo(ResumePdfTextCandidateSelector.CandidateType.LEGACY);
        assertThat(selection.text()).isEqualTo("Name\nemail@example.com");
    }

    @Test
    void healthyLegacyShouldBeatFragmentedPositionCandidate() {
        String legacy = "Alex Chen\nalex@example.com\n+8613812345678\nExperience\nBuilt Java services";
        String fragmented = "A\nl\ne\nx\nC\nh\ne\nn\na\nl\ne\nx\n@\ne\nx\na\nm\np\nl\ne\n.\nc\no\nm";

        var selection = selector.select(legacy, fragmented);

        assertThat(selection.candidateType()).isEqualTo(ResumePdfTextCandidateSelector.CandidateType.LEGACY);
        assertThat(selection.legacyScore()).isGreaterThan(selection.positionScore());
    }

    @Test
    void nonStandardEnglishSectionOrderShouldNotBePenalized() {
        String legacy = "Education\nSkills\nProjects\nExperience\nAlex Chen\nalex@example.com";
        String positionSorted = "Experience\nEducation\nSkills\nProjects\nAlex Chen\nalex@example.com";

        var selection = selector.select(legacy, positionSorted);

        assertThat(selection.candidateType()).isEqualTo(ResumePdfTextCandidateSelector.CandidateType.LEGACY);
        assertThat(selection.legacyScore()).isEqualTo(selection.positionScore());
    }

    @Test
    void chineseGraduateSectionOrderShouldRemainHealthy() {
        String candidate = "教育经历\n专业技能\n项目经历\n实习经历\n陈晓\nchen@example.com";

        var selection = selector.select(candidate, candidate);

        assertThat(selection.candidateType()).isEqualTo(ResumePdfTextCandidateSelector.CandidateType.LEGACY);
        assertThat(selection.legacyScore()).isGreaterThan(0);
    }

    @Test
    void skillsExperienceProjectsEducationOrderShouldRemainHealthy() {
        String candidate = "Skills\nExperience\nProjects\nEducation\nAlex Chen\nalex@example.com";

        var selection = selector.select(candidate, candidate);

        assertThat(selection.candidateType()).isEqualTo(ResumePdfTextCandidateSelector.CandidateType.LEGACY);
        assertThat(selection.legacyScore()).isGreaterThan(0);
    }

    @Test
    void clearlyHealthierPositionSortedCandidateShouldWin() {
        String fragmentedLegacy = "x\n".repeat(30);
        String healthyPositionSorted = "Alex Chen\nalex@example.com\nExperience\nBuilt Java services";

        var selection = selector.select(fragmentedLegacy, healthyPositionSorted);

        assertThat(selection.candidateType()).isEqualTo(ResumePdfTextCandidateSelector.CandidateType.POSITION_SORTED);
        assertThat(selection.positionScore()).isGreaterThan(selection.legacyScore());
    }

    @Test
    void availableCandidateWinsWhenTheOtherExtractionFailed() {
        var selection = selector.select(null, "Only usable extracted text");

        assertThat(selection.candidateType()).isEqualTo(ResumePdfTextCandidateSelector.CandidateType.POSITION_SORTED);
        assertThat(selection.text()).isEqualTo("Only usable extracted text");
    }

    @Test
    void headingCountShouldNotOvercomeSevereFragmentation() {
        String healthyWithoutHeadings = "Alex Chen\nalex@example.com\nJava Spring Boot\nBuilt distributed services\nImproved reliability";
        String fragmentedWithHeadings = "Experience\nProjects\nEducation\nSkills\nAwards\nSummary\n"
                + "x\n".repeat(24);

        var selection = selector.select(healthyWithoutHeadings, fragmentedWithHeadings);

        assertThat(selection.candidateType()).isEqualTo(ResumePdfTextCandidateSelector.CandidateType.LEGACY);
        assertThat(selection.legacyScore()).isGreaterThan(selection.positionScore());
    }

    @Test
    void textWithoutStandardHeadingsDoesNotLoseLegacyStability() {
        String legacy = "Alex Chen\nalex@example.com\nJava Spring Boot\nBuilt reliable services";

        var selection = selector.select(legacy, legacy);

        assertThat(selection.candidateType()).isEqualTo(ResumePdfTextCandidateSelector.CandidateType.LEGACY);
    }
}
