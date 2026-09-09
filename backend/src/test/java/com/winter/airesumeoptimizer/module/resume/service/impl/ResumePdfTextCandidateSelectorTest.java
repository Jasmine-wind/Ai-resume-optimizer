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
    void clearlyBetterHeadingOrderMaySelectPositionSorted() {
        String legacy = "Projects\nSummary\nEducation\nSkills\nAlex Chen alex@example.com";
        String positionSorted = "Summary\nProjects\nEducation\nSkills\nAlex Chen alex@example.com";

        var selection = selector.select(legacy, positionSorted);

        assertThat(selection.candidateType()).isEqualTo(ResumePdfTextCandidateSelector.CandidateType.POSITION_SORTED);
        assertThat(selection.positionScore() - selection.legacyScore()).isGreaterThanOrEqualTo(8);
    }

    @Test
    void availableCandidateWinsWhenTheOtherExtractionFailed() {
        var selection = selector.select(null, "Only usable extracted text");

        assertThat(selection.candidateType()).isEqualTo(ResumePdfTextCandidateSelector.CandidateType.POSITION_SORTED);
        assertThat(selection.text()).isEqualTo("Only usable extracted text");
    }

    @Test
    void textWithoutStandardHeadingsDoesNotLoseLegacyStability() {
        String legacy = "Alex Chen\nalex@example.com\nJava Spring Boot\nBuilt reliable services";

        var selection = selector.select(legacy, legacy);

        assertThat(selection.candidateType()).isEqualTo(ResumePdfTextCandidateSelector.CandidateType.LEGACY);
    }
}
