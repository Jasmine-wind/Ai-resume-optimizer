# Front-end design references

This note records the product-design patterns used as guardrails for the CV-Role front-end polish. The implementation intentionally translates the patterns rather than copying any site literally.

## Reference patterns

### Linear — quiet application chrome

- Keep navigation and secondary controls visually quiet so the current task remains dominant.
- Use one restrained accent color and let typography, spacing, and state changes carry most of the hierarchy.
- Prefer short, purposeful transitions over decorative motion.

### Vercel — disciplined typography and borders

- Use compact type scales, generous breathing room, fine borders, and a small elevation range.
- Treat empty space as structure instead of filling every region with cards.
- Keep call-to-action contrast clear without turning every button into a primary action.

### Raycast — command-oriented feedback

- Make keyboard focus obvious and maintain predictable control placement.
- Give loading, success, warning, and empty states distinct visual treatment.
- Keep interaction feedback immediate and subtle.

### GitHub — familiar, durable states

- Use recognizable labels, status chips, and progressive disclosure.
- Preserve visible focus and readable contrast in both light and dark environments.
- Let dense information remain scannable through alignment and grouping rather than oversized decoration.

## CV-Role translation

- A neutral canvas with layered surfaces and a single violet accent family.
- Shared tokens for spacing, radius, borders, shadows, and semantic states.
- Strong keyboard focus, a main-content target, reduced-motion support, and mobile touch targets.
- Reusable composition utilities that can be adopted incrementally instead of forcing a component rewrite.

## Review checklist

1. The primary task is visually obvious within three seconds.
2. Secondary actions do not compete with the main call to action.
3. Keyboard users can see focus and reach the main content quickly.
4. Narrow screens keep controls at a comfortable touch size without horizontal overflow.
5. Motion communicates state and disappears under `prefers-reduced-motion`.
6. New component colors and spacing values come from shared tokens rather than one-off literals.
