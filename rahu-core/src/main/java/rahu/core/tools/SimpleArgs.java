package rahu.core.tools;

/**
 * Flat-JSON argument reader for tool calls (core stays JDK-only). Works on the
 * canonical form produced by CanonicalJson: `{"key":value,...}` with sorted
 * keys and no insignificant whitespace. Only flat objects are tool arguments.
 */
final class SimpleArgs {

    private final String canonical;

    private SimpleArgs(String canonical) {
        this.canonical = canonical == null ? "" : canonical;
    }

    static SimpleArgs parse(String canonicalJson) {
        return new SimpleArgs(canonicalJson);
    }

    String string(String key) {
        String needle = "\"" + key + "\":\"";
        int start = canonical.indexOf(needle);
        if (start < 0) {
            return null;
        }
        int from = start + needle.length();
        StringBuilder out = new StringBuilder();
        int i = from;
        while (i < canonical.length()) {
            char c = canonical.charAt(i);
            if (c == '\\' && i + 1 < canonical.length()) {
                char esc = canonical.charAt(++i);
                out.append(switch (esc) {
                    case 'n' -> '\n';
                    case 't' -> '\t';
                    case 'r' -> '\r';
                    case '"' -> '"';
                    case '\\' -> '\\';
                    case '/' -> '/';
                    default -> esc;
                });
                i++;
            } else if (c == '"') {
                return out.toString();
            } else {
                out.append(c);
                i++;
            }
        }
        return out.toString();
    }

    Integer integer(String key) {
        String needle = "\"" + key + "\":";
        int start = canonical.indexOf(needle);
        if (start < 0) {
            return null;
        }
        int from = start + needle.length();
        int end = from;
        while (end < canonical.length() && "-0123456789".indexOf(canonical.charAt(end)) >= 0) {
            end++;
        }
        if (end == from) {
            return null;
        }
        try {
            return Integer.parseInt(canonical.substring(from, end));
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
