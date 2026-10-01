package rahu.core.privacy;

/** A scanner finding: category and count only — never the offending value. */
public record Finding(String category, int count) {

    public Finding {
        if (category == null || category.isBlank()) {
            throw new IllegalArgumentException("category required");
        }
        if (count <= 0) {
            throw new IllegalArgumentException("count must be positive");
        }
    }
}
