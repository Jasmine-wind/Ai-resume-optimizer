package com.winter.airesumeoptimizer.module.resume.service.impl;

import com.winter.airesumeoptimizer.module.resume.dto.ResumeBlockDTO;
import com.winter.airesumeoptimizer.module.resume.dto.ResumeTextCleanResultDTO;
import com.winter.airesumeoptimizer.module.resume.dto.ResumeTextSectionDTO;
import com.winter.airesumeoptimizer.module.resume.dto.SourceSectionConfidence;
import com.winter.airesumeoptimizer.module.resume.service.ResumeBlockBuilder;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;

@Service
public class ResumeBlockBuilderImpl implements ResumeBlockBuilder {

    private static final int MAX_BLOCK_TEXT_LENGTH = 500;
    private static final Pattern EMAIL_PATTERN = Pattern.compile("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}");
    private static final Pattern PHONE_PATTERN = Pattern.compile("(?<!\\d)(?:\\(\\+?86\\)|\\+?86|86)?[-\\s]*1[3-9]\\d[-\\s]?\\d{4}[-\\s]?\\d{4}(?!\\d)");
    private static final Pattern GITHUB_PATTERN = Pattern.compile("(?i)(?:https?://)?github\\.com/[A-Za-z0-9_.-]+");
    private static final Set<String> LOCKED_SOURCE_SECTIONS = Set.of(
            "BASIC_INFO",
            "EDUCATION",
            "SKILLS",
            "WORK_EXPERIENCES",
            "INTERNSHIPS",
            "PROJECTS",
            "CAMPUS_EXPERIENCES",
            "AWARDS",
            "CERTIFICATES",
            "SUMMARY");

    @Override
    public List<ResumeBlockDTO> build(ResumeTextCleanResultDTO cleanResult) {
        if (cleanResult == null || cleanResult.getSections() == null || cleanResult.getSections().isEmpty()) {
            return List.of();
        }

        List<ResumeBlockDTO> blocks = new ArrayList<>();
        int index = 0;
        for (ResumeTextSectionDTO section : cleanResult.getSections()) {
            if (section.getLines() == null) {
                continue;
            }
            for (String line : section.getLines()) {
                String text = normalizeBlockText(line);
                if (!shouldKeep(text)) {
                    continue;
                }
                String sectionType = section.getSectionType();
                SourceSectionConfidence sourceConfidence = sourceSectionConfidence(section);
                String iconType = resolveIconType(section, text);
                for (String fragment : splitBlockText(text)) {
                    blocks.add(ResumeBlockDTO.builder()
                            .index(index++)
                            .originalIndex(index - 1)
                            .displayOrder(index - 1)
                            .text(fragment)
                            .sourceType("cleanedText")
                            .iconType(iconType)
                            .sourceSection(sectionType)
                            .ruleSection(normalizeRuleSection(sectionType))
                            .ruleConfidence(ruleConfidence(sourceConfidence, sectionType))
                            .sourceSectionConfidence(sourceConfidence.name())
                            .lockedLevel(sourceConfidence.name())
                            .finalSectionSource("RULE_SOURCE_SECTION")
                            .sectionLocked(sourceConfidence == SourceSectionConfidence.HIGH)
                            .build());
                }
            }
        }
        fillNeighborContext(blocks);
        return blocks;
    }

    private void fillNeighborContext(List<ResumeBlockDTO> blocks) {
        for (int index = 0; index < blocks.size(); index++) {
            ResumeBlockDTO current = blocks.get(index);
            current.setPrevText(index == 0 ? null : blocks.get(index - 1).getText());
            current.setNextText(index + 1 >= blocks.size() ? null : blocks.get(index + 1).getText());
        }
    }

    private boolean isLockedSourceSection(String sectionType) {
        return sectionType != null && LOCKED_SOURCE_SECTIONS.contains(sectionType);
    }

    private String normalizeRuleSection(String sectionType) {
        if (sectionType == null || sectionType.isBlank()) {
            return "OTHERS";
        }
        return "GENERAL".equals(sectionType) ? "OTHERS" : sectionType;
    }

    private double ruleConfidence(SourceSectionConfidence sourceConfidence, String sectionType) {
        return switch (sourceConfidence) {
            case HIGH -> 0.95;
            case MEDIUM -> 0.72;
            case LOW -> "OTHERS".equals(sectionType) ? 0.55 : 0.35;
        };
    }

    private SourceSectionConfidence sourceSectionConfidence(ResumeTextSectionDTO section) {
        SourceSectionConfidence explicitConfidence = SourceSectionConfidence.from(section.getSourceSectionConfidence());
        if (explicitConfidence != SourceSectionConfidence.LOW || section.getSourceSectionConfidence() != null) {
            return explicitConfidence;
        }
        if (isLockedSourceSection(section.getSectionType())) {
            return SourceSectionConfidence.HIGH;
        }
        return SourceSectionConfidence.LOW;
    }

    private String normalizeBlockText(String value) {
        if (value == null) {
            return "";
        }
        return value.replaceAll("[\\t\\x0B\\f\\r 　]+", " ")
                .replaceFirst("^[\\s>*•·●▪■◆◇○◦▶►✓✔-]+", "")
                .strip();
    }

    private String resolveIconType(ResumeTextSectionDTO section, String text) {
        if (EMAIL_PATTERN.matcher(text).find()) {
            return "EMAIL_ICON";
        }
        if (PHONE_PATTERN.matcher(text).find()) {
            return "PHONE_ICON";
        }
        if (GITHUB_PATTERN.matcher(text).find()) {
            return "GITHUB_ICON";
        }
        return section == null ? null : section.getIconType();
    }

    private boolean shouldKeep(String text) {
        if (text.isBlank()) {
            return false;
        }
        if (text.length() >= 2) {
            return true;
        }
        return EMAIL_PATTERN.matcher(text).find()
                || PHONE_PATTERN.matcher(text).find()
                || text.contains("本科")
                || text.contains("硕士")
                || text.contains("博士");
    }

    List<String> splitBlockText(String text) {
        if (text == null || text.length() <= MAX_BLOCK_TEXT_LENGTH) {
            return text == null ? List.of() : List.of(text);
        }
        List<String> fragments = new ArrayList<>();
        int start = 0;
        while (text.length() - start > MAX_BLOCK_TEXT_LENGTH) {
            int end = findSplitPoint(text, start);
            fragments.add(text.substring(start, end));
            start = end;
        }
        fragments.add(text.substring(start));
        return fragments;
    }

    private int findSplitPoint(String text, int start) {
        int limit = Math.min(text.length(), start + MAX_BLOCK_TEXT_LENGTH);
        int windowStart = Math.min(limit, start + 300);
        String[] delimiterGroups = {"。！？!?；;", "，,", " "};
        for (String delimiters : delimiterGroups) {
            for (int index = limit - 1; index >= windowStart; index--) {
                if (delimiters.indexOf(text.charAt(index)) >= 0) {
                    return safeCodePointBoundary(text, index + 1);
                }
            }
        }
        return safeCodePointBoundary(text, limit);
    }

    private int safeCodePointBoundary(String text, int boundary) {
        if (boundary > 0 && boundary < text.length()
                && Character.isHighSurrogate(text.charAt(boundary - 1))
                && Character.isLowSurrogate(text.charAt(boundary))) {
            return boundary - 1;
        }
        return boundary;
    }
}
