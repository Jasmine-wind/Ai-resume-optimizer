package com.winter.airesumeoptimizer.module.workspace.service.impl;

import com.winter.airesumeoptimizer.module.workspace.service.RewriteMaterialityGate;
import java.text.Normalizer;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;

/**
 * Conservative materiality check for the narrow "changed only to produce an answer" class.
 *
 * <p>It intentionally does not calculate similarity or infer semantics. A change is low value
 * only when the two strings become identical after removing formatting, punctuation, or a small
 * allowlist of Chinese connective / function words. Fact changes such as 参与 → 主导 or a real
 * reordering therefore remain the responsibility of the fact validator and are never swallowed
 * by this gate.
 */
@Service
public class RewriteMaterialityGateImpl implements RewriteMaterialityGate {

    private static final Pattern FORMATTING_PATTERN = Pattern.compile("[\\p{P}\\p{S}\\s]+");
    private static final Pattern LOW_VALUE_STYLE_TOKEN_PATTERN = Pattern.compile(
            "并且|以及|并(?![发行])|且|的|了");

    @Override
    public boolean isLowValueChange(String originalText, String suggestedText) {
        if (originalText == null || suggestedText == null) {
            return false;
        }

        String original = normalize(originalText);
        String suggested = normalize(suggestedText);
        if (original.equals(suggested)) {
            return true;
        }

        String originalWithoutFormatting = removeFormatting(original);
        String suggestedWithoutFormatting = removeFormatting(suggested);
        if (originalWithoutFormatting.equals(suggestedWithoutFormatting)) {
            return true;
        }

        return removeStyleTokens(originalWithoutFormatting)
                .equals(removeStyleTokens(suggestedWithoutFormatting));
    }

    private String normalize(String value) {
        return Normalizer.normalize(value, Normalizer.Form.NFKC).strip();
    }

    private String removeFormatting(String value) {
        return FORMATTING_PATTERN.matcher(value).replaceAll("");
    }

    private String removeStyleTokens(String value) {
        return LOW_VALUE_STYLE_TOKEN_PATTERN.matcher(value).replaceAll("");
    }
}
