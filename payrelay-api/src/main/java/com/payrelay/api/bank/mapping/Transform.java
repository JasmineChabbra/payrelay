package com.payrelay.api.bank.mapping;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * A single value transformation, written in config as {@code NAME} or {@code NAME:argument},
 * e.g. {@code UPPER}, {@code TRUNCATE:35}, {@code LOOKUP:purposeCodes}.
 */
public enum Transform {
    UPPER("Upper-case the text", false),
    LOWER("Lower-case the text", false),
    TRIM("Remove leading and trailing spaces", false),
    PAISE("Rupees to paise as a whole number (25000.50 becomes 2500050)", false),
    DECIMAL_STRING("Amount as text with two decimals (\"25000.50\")", false),
    STRING("Convert to text", false),
    TRUNCATE("Cut text to at most N characters, e.g. TRUNCATE:35", true),
    LOOKUP("Replace the value using a lookup table, e.g. LOOKUP:purposeCodes. A \"*\" row is the fallback", true);

    public final String description;
    public final boolean takesArgument;

    Transform(String description, boolean takesArgument) {
        this.description = description;
        this.takesArgument = takesArgument;
    }

    /** A transform with its argument, parsed from config text. */
    public record Step(Transform transform, String argument) {

        public static Step parse(String text) {
            if (text == null || text.isBlank()) {
                throw new IllegalArgumentException("empty transform");
            }
            String[] parts = text.trim().split(":", 2);
            Transform t;
            try {
                t = Transform.valueOf(parts[0].trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("unknown transform '" + parts[0].trim() + "'");
            }
            String arg = parts.length > 1 ? parts[1].trim() : null;
            if (t.takesArgument && (arg == null || arg.isEmpty())) {
                throw new IllegalArgumentException(t + " needs an argument, e.g. " + (t == TRUNCATE ? "TRUNCATE:35" : "LOOKUP:tableName"));
            }
            if (!t.takesArgument && arg != null) {
                throw new IllegalArgumentException(t + " doesn't take an argument");
            }
            if (t == TRUNCATE) {
                try {
                    if (Integer.parseInt(arg) < 1) {
                        throw new NumberFormatException();
                    }
                } catch (NumberFormatException e) {
                    throw new IllegalArgumentException("TRUNCATE needs a positive whole number, got '" + arg + "'");
                }
            }
            return new Step(t, arg);
        }

        Object apply(Object value, Map<String, Map<String, String>> lookups) {
            return switch (transform) {
                case UPPER -> value.toString().toUpperCase(Locale.ROOT);
                case LOWER -> value.toString().toLowerCase(Locale.ROOT);
                case TRIM -> value.toString().trim();
                case STRING -> value.toString();
                case PAISE -> toPaise(value);
                case DECIMAL_STRING -> decimal(value).setScale(2, RoundingMode.UNNECESSARY).toPlainString();
                case TRUNCATE -> {
                    String s = value.toString();
                    int max = Integer.parseInt(argument);
                    yield s.length() <= max ? s : s.substring(0, max);
                }
                case LOOKUP -> {
                    Map<String, String> table = lookups.get(argument);
                    if (table == null) {
                        throw new IllegalArgumentException("lookup table '" + argument + "' does not exist");
                    }
                    String key = value.toString();
                    String mapped = table.containsKey(key) ? table.get(key) : table.get("*");
                    if (mapped == null) {
                        throw new IllegalArgumentException("no entry for '" + key + "' in lookup '" + argument + "' and no '*' fallback");
                    }
                    yield mapped;
                }
            };
        }

        private static long toPaise(Object value) {
            try {
                return decimal(value).movePointRight(2).setScale(0, RoundingMode.UNNECESSARY).longValueExact();
            } catch (ArithmeticException e) {
                throw new IllegalArgumentException("amount " + value + " has fractions of a paisa");
            }
        }

        private static BigDecimal decimal(Object value) {
            if (value instanceof BigDecimal b) {
                return b;
            }
            try {
                return new BigDecimal(value.toString().trim());
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("'" + value + "' is not a number");
            }
        }

        @Override
        public String toString() {
            return argument == null ? transform.name() : transform.name() + ":" + argument;
        }
    }

    public static List<Step> parseAll(List<String> texts) {
        return texts.stream().map(Step::parse).toList();
    }
}
