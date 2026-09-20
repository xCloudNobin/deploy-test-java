package dev.xcloudnobin.taskboard;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Minimal JSON reader/writer. No third-party JSON library is needed; this
 * fixture's payloads are small. The writer escapes output so values can be
 * embedded safely, and the parser rejects invalid documents with a clear
 * message (used for 400 validation errors).
 */
public final class Json {
    private Json() {
    }

    // ------------------------------------------------------------------
    // Writing
    // ------------------------------------------------------------------

    public static String write(Object value) {
        StringBuilder sb = new StringBuilder();
        writeValue(sb, value);
        return sb.toString();
    }

    private static void writeValue(StringBuilder sb, Object value) {
        if (value == null) {
            sb.append("null");
        } else if (value instanceof String s) {
            writeString(sb, s);
        } else if (value instanceof Number n) {
            if (n instanceof Double || n instanceof Float) {
                double d = n.doubleValue();
                if (Double.isInfinite(d) || Double.isNaN(d)) {
                    sb.append("null");
                } else {
                    sb.append(d);
                }
            } else {
                sb.append(n);
            }
        } else if (value instanceof Boolean b) {
            sb.append(b);
        } else if (value instanceof Map<?, ?> map) {
            sb.append('{');
            boolean first = true;
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (!first) {
                    sb.append(',');
                }
                first = false;
                writeString(sb, String.valueOf(entry.getKey()));
                sb.append(':');
                writeValue(sb, entry.getValue());
            }
            sb.append('}');
        } else if (value instanceof Iterable<?> it) {
            sb.append('[');
            boolean first = true;
            for (Object item : it) {
                if (!first) {
                    sb.append(',');
                }
                first = false;
                writeValue(sb, item);
            }
            sb.append(']');
        } else {
            writeString(sb, value.toString());
        }
    }

    private static void writeString(StringBuilder sb, String s) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\b' -> sb.append("\\b");
                case '\f' -> sb.append("\\f");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        sb.append('"');
    }

    // ------------------------------------------------------------------
    // Reading
    // ------------------------------------------------------------------

    public static Object parse(String text) {
        Parser p = new Parser(text);
        Object value = p.parseValue();
        p.skipWhitespace();
        if (!p.atEnd()) {
            throw new JsonParseException("Unexpected trailing content at " + p.pos);
        }
        return value;
    }

    /** Returns the parsed value as a Map, or throws if it is not an object. */
    public static Map<String, Object> parseObject(String text) {
        Object value = parse(text);
        if (!(value instanceof Map<?, ?> m)) {
            throw new JsonParseException("Expected a JSON object");
        }
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<?, ?> e : m.entrySet()) {
            out.put(String.valueOf(e.getKey()), e.getValue());
        }
        return out;
    }

    public static final class JsonParseException extends RuntimeException {
        public JsonParseException(String message) {
            super(message);
        }
    }

    private static final class Parser {
        private final String text;
        private int pos;

        Parser(String text) {
            this.text = text;
        }

        boolean atEnd() {
            return pos >= text.length();
        }

        void skipWhitespace() {
            while (!atEnd()) {
                char c = text.charAt(pos);
                if (c == ' ' || c == '\t' || c == '\n' || c == '\r') {
                    pos++;
                } else {
                    break;
                }
            }
        }

        char peek() {
            if (atEnd()) {
                throw error("Unexpected end of input");
            }
            return text.charAt(pos);
        }

        char next() {
            if (atEnd()) {
                throw error("Unexpected end of input");
            }
            return text.charAt(pos++);
        }

        JsonParseException error(String message) {
            return new JsonParseException(message + " at " + pos);
        }

        Object parseValue() {
            skipWhitespace();
            char c = peek();
            switch (c) {
                case '{' -> {
                    return parseObject();
                }
                case '[' -> {
                    return parseArray();
                }
                case '"' -> {
                    return parseString();
                }
                case 't', 'f' -> {
                    return parseBoolean();
                }
                case 'n' -> {
                    parseKeyword("null");
                    return null;
                }
                default -> {
                    if (c == '-' || (c >= '0' && c <= '9')) {
                        return parseNumber();
                    }
                    throw error("Unexpected character '" + c + "'");
                }
            }
        }

        Map<String, Object> parseObject() {
            expect('{');
            Map<String, Object> map = new LinkedHashMap<>();
            skipWhitespace();
            if (peek() == '}') {
                next();
                return map;
            }
            while (true) {
                skipWhitespace();
                String key = parseString();
                skipWhitespace();
                expect(':');
                Object value = parseValue();
                map.put(key, value);
                skipWhitespace();
                char c = next();
                if (c == ',') {
                    continue;
                }
                if (c == '}') {
                    return map;
                }
                throw error("Expected ',' or '}'");
            }
        }

        List<Object> parseArray() {
            expect('[');
            List<Object> list = new ArrayList<>();
            skipWhitespace();
            if (peek() == ']') {
                next();
                return list;
            }
            while (true) {
                list.add(parseValue());
                skipWhitespace();
                char c = next();
                if (c == ',') {
                    continue;
                }
                if (c == ']') {
                    return list;
                }
                throw error("Expected ',' or ']'");
            }
        }

        String parseString() {
            expect('"');
            StringBuilder sb = new StringBuilder();
            while (true) {
                char c = next();
                if (c == '"') {
                    return sb.toString();
                }
                if (c == '\\') {
                    char e = next();
                    switch (e) {
                        case '"' -> sb.append('"');
                        case '\\' -> sb.append('\\');
                        case '/' -> sb.append('/');
                        case 'b' -> sb.append('\b');
                        case 'f' -> sb.append('\f');
                        case 'n' -> sb.append('\n');
                        case 'r' -> sb.append('\r');
                        case 't' -> sb.append('\t');
                        case 'u' -> {
                            if (pos + 4 > text.length()) {
                                throw error("Truncated unicode escape");
                            }
                            String hex = text.substring(pos, pos + 4);
                            try {
                                sb.append((char) Integer.parseInt(hex, 16));
                            } catch (NumberFormatException ex) {
                                throw error("Invalid unicode escape \\u" + hex);
                            }
                            pos += 4;
                        }
                        default -> throw error("Invalid escape '\\" + e + "'");
                    }
                } else if (c < 0x20) {
                    throw error("Unescaped control character in string");
                } else {
                    sb.append(c);
                }
            }
        }

        Boolean parseBoolean() {
            if (peek() == 't') {
                parseKeyword("true");
                return Boolean.TRUE;
            }
            parseKeyword("false");
            return Boolean.FALSE;
        }

        Number parseNumber() {
            int start = pos;
            if (peek() == '-') {
                next();
            }
            boolean isFloat = false;
            while (!atEnd()) {
                char c = text.charAt(pos);
                if (c >= '0' && c <= '9') {
                    next();
                } else if (c == '.' || c == 'e' || c == 'E') {
                    isFloat = true;
                    next();
                } else if ((c == '+' || c == '-') && pos > start
                        && (text.charAt(pos - 1) == 'e' || text.charAt(pos - 1) == 'E')) {
                    next();
                } else {
                    break;
                }
            }
            String token = text.substring(start, pos);
            if (token.isEmpty() || token.equals("-")) {
                throw error("Invalid number");
            }
            try {
                if (isFloat) {
                    return Double.parseDouble(token);
                }
                return Long.parseLong(token);
            } catch (NumberFormatException ex) {
                throw error("Invalid number '" + token + "'");
            }
        }

        void expect(char c) {
            if (atEnd() || text.charAt(pos) != c) {
                throw error("Expected '" + c + "'");
            }
            pos++;
        }

        void parseKeyword(String keyword) {
            if (pos + keyword.length() > text.length()
                    || !text.startsWith(keyword, pos)) {
                throw error("Invalid token");
            }
            pos += keyword.length();
        }
    }
}