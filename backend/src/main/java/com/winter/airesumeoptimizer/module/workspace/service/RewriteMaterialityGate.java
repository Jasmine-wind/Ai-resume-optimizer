package com.winter.airesumeoptimizer.module.workspace.service;

/**
 * Deterministic post-validation gate for obviously low-value rewrite changes.
 *
 * <p>This is deliberately separate from {@link RewriteFactValidator}: it does not decide
 * whether facts are safe, only whether the approved text gives the user enough visible value
 * to warrant a suggestion card.
 */
public interface RewriteMaterialityGate {

    boolean isLowValueChange(String originalText, String suggestedText);
}
