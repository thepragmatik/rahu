package rahu.core.tools;

/**
 * Deterministic JSON canonicalization (sorted keys, no insignificant whitespace)
 * shared by the no-progress fingerprinter and tool-call dedup. Core is JDK-only
 * by architecture, so this is a small local parser, not a JSON library
 * dependency. Accepts RFC 8259 JSON; malformed input returns the raw string
 * (still deterministic).
 */
public final class CanonicalJson {

    private CanonicalJson() {
    }

    public static String canonicalize(String json) {
        if (json == null) {
            return "";
        }
        try {
            Parser p = new Parser(json);
            p.skipWs();
            String value = p.parseValue();
            p.skipWs();
            if (!p.atEnd()) {
                return json;
            }
            return value;
        } catch (Exception e) {
            return json;
        }
    }

    private static final class Parser {
        private final String s;
        private int i;

        Parser(String s) {
            this.s = s;
        }

        boolean atEnd() {
            return i >= s.length();
        }

        void skipWs() {
            while (i < s.length() && Character.isWhitespace(s.charAt(i))) {
                i++;
            }
        }

        char peek() {
            return s.charAt(i);
        }

        String parseValue() {
            skipWs();
            char c = peek();
            return switch (c) {
                case '{' -> parseObject();
                case '[' -> parseArray();
                case '"' -> parseString();
                default -> parseLiteral();
            };
        }

        private String parseObject() {
            StringBuilder out = new StringBuilder("{");
            i++; // {
            skipWs();
            if (!atEnd() && peek() == '}') {
                i++;
                return out.append("}").toString();
            }
            var pairs = new java.util.ArrayList<String>();
            while (true) {
                skipWs();
                String key = parseString();
                skipWs();
                expect(':');
                i++; // :
                String value = parseValue();
                pairs.add(key + ":" + value);
                skipWs();
                char c = peek();
                if (c == ',') {
                    i++;
                } else if (c == '}') {
                    i++;
                    break;
                } else {
                    throw new IllegalArgumentException("bad object at " + i);
                }
            }
            pairs.sort(String::compareTo);
            for (int k = 0; k < pairs.size(); k++) {
                if (k > 0) {
                    out.append(',');
                }
                out.append(pairs.get(k));
            }
            return out.append("}").toString();
        }

        private String parseArray() {
            StringBuilder out = new StringBuilder("[");
            i++; // [
            skipWs();
            if (!atEnd() && peek() == ']') {
                i++;
                return out.append("]").toString();
            }
            while (true) {
                skipWs();
                out.append(parseValue());
                skipWs();
                char c = peek();
                if (c == ',') {
                    out.append(',');
                    i++;
                } else if (c == ']') {
                    i++;
                    break;
                } else {
                    throw new IllegalArgumentException("bad array at " + i);
                }
            }
            return out.append("]").toString();
        }

        private String parseString() {
            StringBuilder out = new StringBuilder("\"");
            expect('"');
            i++; // opening quote
            while (!atEnd()) {
                char c = s.charAt(i++);
                if (c == '"') {
                    return out.append('"').toString();
                }
                if (c == '\\') {
                    char esc = s.charAt(i++);
                    out.append('\\').append(esc);
                    if (esc == 'u') {
                        out.append(s, i, i + 4);
                        i += 4;
                    }
                } else {
                    out.append(c);
                }
            }
            throw new IllegalArgumentException("unterminated string");
        }

        private String parseLiteral() {
            int start = i;
            while (!atEnd() && ",]} \t\r\n".indexOf(s.charAt(i)) < 0) {
                i++;
            }
            return s.substring(start, i);
        }

        private void expect(char c) {
            if (atEnd() || s.charAt(i) != c) {
                throw new IllegalArgumentException("expected " + c + " at " + i);
            }
        }
    }
}
