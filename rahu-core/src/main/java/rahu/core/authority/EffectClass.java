package rahu.core.authority;

/** Effect classes (tools.md). Only READ_ONLY is admissible in alpha. */
public enum EffectClass {
    READ_ONLY,
    LOCAL_REVERSIBLE_WRITE,
    EXTERNAL_EFFECT,
    DESTRUCTIVE
}
