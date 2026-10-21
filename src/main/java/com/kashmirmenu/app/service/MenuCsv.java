package com.kashmirmenu.app.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Reads a menu from CSV (what Excel, Google Sheets and Numbers all export). Pure: no database, so every
 * rule is testable, and the server is the single place a file is understood. The preview a restaurant
 * sees and the import it confirms both come from this parser, and the import parses the file AGAIN
 * rather than trusting anything the browser sent back.
 *
 * <p>Columns (any order; headers are case-insensitive and a few common spellings are accepted):
 * <pre>name, category, price, veg, description, available, prep_time</pre>
 * {@code name}, {@code category}, {@code price} and {@code veg} are required. {@code veg} must be
 * stated for EVERY row: a guessed default could mislabel meat as vegetarian, which is a dietary and
 * religious matter, so nothing is assumed. {@code available} defaults to yes and {@code prep_time} to 10.
 *
 * <p>RFC 4180 quoting is supported (commas, quotes and line breaks inside quoted fields). A file with
 * any problem produces problems and the caller imports nothing: an owner fixes the file once instead of
 * finding half a menu loaded.
 */
public final class MenuCsv {

    public static final int MAX_ROWS = 500;
    public static final int MAX_CHARS = 1_000_000;
    public static final int MAX_NAME = 150;
    public static final int MAX_CATEGORY = 100;
    public static final int MAX_DESCRIPTION = 1000;
    public static final int MAX_PREP_MINUTES = 300;
    public static final int DEFAULT_PREP_MINUTES = 10;
    public static final BigDecimal MAX_PRICE = new BigDecimal("99999.99");

    private MenuCsv() {
    }

    /** One valid dish row. {@code line} is the row's line number in the file (the header is line 1). */
    public record Row(int line, String category, String name, String description, BigDecimal price,
                      boolean veg, boolean available, int prepMinutes) {
    }

    /** Something to fix. {@code line} 0 means the file as a whole. */
    public record Problem(int line, String field, String message) {
    }

    public record Result(List<Row> rows, List<Problem> problems) {
        public boolean ok() {
            return problems.isEmpty();
        }
    }

    private static final Map<String, Set<String>> ALIASES = Map.of(
            "name", Set.of("name", "item", "item_name", "dish", "dish_name"),
            "category", Set.of("category", "category_name", "section", "menu_section"),
            "price", Set.of("price", "rate", "amount", "price_inr", "cost"),
            "veg", Set.of("veg", "vegetarian", "is_veg", "veg_nonveg", "veg_or_non_veg", "type"),
            "description", Set.of("description", "desc", "details", "about"),
            "available", Set.of("available", "availability", "in_stock"),
            "prep_time", Set.of("prep_time", "preparation_time", "prep_minutes", "prep"));

    private static final Set<String> YES = Set.of("yes", "y", "true", "1", "available", "in stock");
    private static final Set<String> NO = Set.of("no", "n", "false", "0", "unavailable", "sold out", "out of stock");
    private static final Set<String> VEG = Set.of("veg", "vegetarian", "v", "yes", "y", "true");
    private static final Set<String> NON_VEG = Set.of("non-veg", "non veg", "nonveg", "non_veg", "non-vegetarian",
            "nonvegetarian", "nv", "no", "n", "false", "meat", "egg");

    public static Result parse(String text) {
        List<Problem> problems = new ArrayList<>();
        List<Row> rows = new ArrayList<>();

        if (text == null || text.isBlank()) {
            problems.add(new Problem(0, "file", "The file is empty."));
            return new Result(rows, problems);
        }
        if (text.length() > MAX_CHARS) {
            problems.add(new Problem(0, "file", "The file is too large (limit " + (MAX_CHARS / 1000) + " KB)."));
            return new Result(rows, problems);
        }

        List<Record> records = records(text, problems);
        if (!problems.isEmpty()) {
            return new Result(rows, problems);
        }
        records.removeIf(r -> r.fields.stream().allMatch(String::isBlank));
        if (records.isEmpty()) {
            problems.add(new Problem(0, "file", "The file is empty."));
            return new Result(rows, problems);
        }

        // ---- header
        Record header = records.get(0);
        Map<String, Integer> col = new HashMap<>();
        for (int i = 0; i < header.fields.size(); i++) {
            String h = header.fields.get(i).strip().toLowerCase(Locale.ROOT).replaceAll("[\\s\\-]+", "_");
            String canonical = null;
            for (var e : ALIASES.entrySet()) {
                if (e.getValue().contains(h)) {
                    canonical = e.getKey();
                }
            }
            if (canonical == null) {
                continue;      // an extra column: ignored
            }
            if (col.containsKey(canonical)) {
                problems.add(new Problem(header.line, canonical, "The column \"" + canonical + "\" appears twice."));
            } else {
                col.put(canonical, i);
            }
        }
        for (String required : List.of("name", "category", "price", "veg")) {
            if (!col.containsKey(required)) {
                problems.add(new Problem(header.line, required, "The first row must have a \""
                        + required + "\" column." + ("veg".equals(required)
                        ? " It must say veg or non-veg for every dish; nothing is assumed." : "")));
            }
        }
        if (!problems.isEmpty()) {
            return new Result(rows, problems);
        }

        // ---- rows
        List<Record> body = records.subList(1, records.size());
        if (body.isEmpty()) {
            problems.add(new Problem(0, "file", "There are no dishes below the header row."));
            return new Result(rows, problems);
        }
        if (body.size() > MAX_ROWS) {
            problems.add(new Problem(0, "file", "A file can have at most " + MAX_ROWS + " dishes; this one has "
                    + body.size() + ". Split it into several files."));
            return new Result(rows, problems);
        }

        Map<String, Integer> seen = new HashMap<>();
        for (Record r : body) {
            List<Problem> mine = new ArrayList<>();
            String name = clean(get(r, col, "name"));
            String category = clean(get(r, col, "category"));
            String description = clean(get(r, col, "description"));

            if (name.isEmpty()) {
                mine.add(new Problem(r.line, "name", "The name is missing."));
            } else if (name.codePointCount(0, name.length()) > MAX_NAME) {
                mine.add(new Problem(r.line, "name", "The name is longer than " + MAX_NAME + " characters."));
            }
            if (category.isEmpty()) {
                mine.add(new Problem(r.line, "category", "The category is missing."));
            } else if (category.codePointCount(0, category.length()) > MAX_CATEGORY) {
                mine.add(new Problem(r.line, "category", "The category is longer than " + MAX_CATEGORY + " characters."));
            }
            if (description.codePointCount(0, description.length()) > MAX_DESCRIPTION) {
                mine.add(new Problem(r.line, "description", "The description is longer than " + MAX_DESCRIPTION + " characters."));
            }

            BigDecimal price = parsePrice(get(r, col, "price"), r.line, mine);
            Boolean veg = parseChoice(get(r, col, "veg"), VEG, NON_VEG);
            if (veg == null) {
                mine.add(new Problem(r.line, "veg", get(r, col, "veg").isBlank()
                        ? "Say veg or non-veg for this dish; nothing is assumed."
                        : "\"" + get(r, col, "veg").strip() + "\" is not veg or non-veg."));
            }

            boolean available = true;
            if (col.containsKey("available") && !get(r, col, "available").isBlank()) {
                Boolean a = parseChoice(get(r, col, "available"), YES, NO);
                if (a == null) {
                    mine.add(new Problem(r.line, "available", "\"" + get(r, col, "available").strip() + "\" is not yes or no."));
                } else {
                    available = a;
                }
            }

            int prep = DEFAULT_PREP_MINUTES;
            String prepText = get(r, col, "prep_time").strip();
            if (!prepText.isEmpty()) {
                try {
                    prep = Integer.parseInt(prepText.replaceAll("(?i)\\s*(min(ute)?s?)$", ""));
                    if (prep < 0 || prep > MAX_PREP_MINUTES) {
                        mine.add(new Problem(r.line, "prep_time", "Preparation time must be 0 to " + MAX_PREP_MINUTES + " minutes."));
                    }
                } catch (NumberFormatException e) {
                    mine.add(new Problem(r.line, "prep_time", "\"" + prepText + "\" is not a whole number of minutes."));
                }
            }

            if (!name.isEmpty() && !category.isEmpty()) {
                String key = category.toLowerCase(Locale.ROOT) + "\u0000" + name.toLowerCase(Locale.ROOT);
                Integer first = seen.putIfAbsent(key, r.line);
                if (first != null) {
                    mine.add(new Problem(r.line, "name", "\"" + name + "\" is already in \"" + category
                            + "\" on line " + first + "."));
                }
            }

            if (mine.isEmpty()) {
                rows.add(new Row(r.line, category, name, description.isEmpty() ? null : description, price,
                        veg, available, prep));
            }
            problems.addAll(mine);
        }
        return new Result(rows, problems);
    }

    // ------------------------------------------------------------------ fields

    private static String get(Record r, Map<String, Integer> col, String key) {
        Integer i = col.get(key);
        return i == null || i >= r.fields.size() ? "" : r.fields.get(i);
    }

    /** Trim, collapse whitespace, and drop control characters. */
    static String clean(String s) {
        return s == null ? "" : s.replaceAll("\\p{Cntrl}", " ").replaceAll("\\s+", " ").strip();
    }

    private static Boolean parseChoice(String raw, Set<String> yes, Set<String> no) {
        String v = clean(raw).toLowerCase(Locale.ROOT);
        if (yes.contains(v)) {
            // "no"/"n" appear in both veg lists as alternatives; the more specific list wins below
            if (no.contains(v)) {
                return null;
            }
            return Boolean.TRUE;
        }
        if (no.contains(v)) {
            return Boolean.FALSE;
        }
        return null;
    }

    private static BigDecimal parsePrice(String raw, int line, List<Problem> out) {
        String s = clean(raw);
        if (s.isEmpty()) {
            out.add(new Problem(line, "price", "The price is missing."));
            return null;
        }
        // Rupee sign, "Rs", "INR", thousands commas and spaces are accepted and removed.
        String digits = s.replaceAll("(?i)(\u20B9|rs\\.?|inr)", "").replace(",", "").replace(" ", "");
        if (!digits.matches("\\d+(\\.\\d+)?")) {
            out.add(new Problem(line, "price", "\"" + s + "\" is not a price."));
            return null;
        }
        BigDecimal p = new BigDecimal(digits);
        if (p.scale() > 2) {
            out.add(new Problem(line, "price", "A price can have at most 2 decimal places."));
            return null;
        }
        p = p.setScale(2, RoundingMode.UNNECESSARY);
        if (p.signum() <= 0) {
            out.add(new Problem(line, "price", "The price must be more than 0."));
            return null;
        }
        if (p.compareTo(MAX_PRICE) > 0) {
            out.add(new Problem(line, "price", "The price cannot be more than " + MAX_PRICE.toPlainString() + "."));
            return null;
        }
        return p;
    }

    // --------------------------------------------------------------- RFC 4180

    private record Record(int line, List<String> fields) {
    }

    /** Splits text into records and fields, honouring quotes. Each record carries its starting line. */
    static List<Record> records(String text, List<Problem> problems) {
        List<Record> out = new ArrayList<>();
        if (!text.isEmpty() && text.charAt(0) == '\uFEFF') {
            text = text.substring(1);            // the byte-order mark Excel writes
        }
        // Excel in some locales separates with semicolons; detect from the header line.
        char delimiter = detectDelimiter(text);

        List<String> fields = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean quoted = false;
        int line = 1, startLine = 1;
        boolean sawAnything = false;
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (quoted) {
                if (ch == '"') {
                    if (i + 1 < text.length() && text.charAt(i + 1) == '"') {
                        cur.append('"');
                        i++;
                    } else {
                        quoted = false;
                    }
                } else {
                    if (ch == '\n') {
                        line++;
                    }
                    cur.append(ch);
                }
                continue;
            }
            if (ch == '"' && cur.length() == 0) {
                quoted = true;
                sawAnything = true;
            } else if (ch == delimiter) {
                fields.add(cur.toString());
                cur.setLength(0);
                sawAnything = true;
            } else if (ch == '\r' || ch == '\n') {
                if (ch == '\r' && i + 1 < text.length() && text.charAt(i + 1) == '\n') {
                    i++;
                }
                fields.add(cur.toString());
                cur.setLength(0);
                out.add(new Record(startLine, fields));
                fields = new ArrayList<>();
                line++;
                startLine = line;
                sawAnything = false;
            } else {
                cur.append(ch);
                sawAnything = true;
            }
        }
        if (quoted) {
            problems.add(new Problem(startLine, "file", "A quotation mark opened on line " + startLine + " is never closed."));
            return out;
        }
        if (sawAnything || !fields.isEmpty() || cur.length() > 0) {
            fields.add(cur.toString());
            out.add(new Record(startLine, fields));
        }
        return out;
    }

    private static char detectDelimiter(String text) {
        int nl = text.indexOf('\n');
        String head = nl < 0 ? text : text.substring(0, nl);
        int commas = 0, semis = 0;
        boolean q = false;
        for (char c : head.toCharArray()) {
            if (c == '"') {
                q = !q;
            } else if (!q && c == ',') {
                commas++;
            } else if (!q && c == ';') {
                semis++;
            }
        }
        return semis > commas ? ';' : ',';
    }

    /** Exposed so the service can describe the set of accepted veg words without duplicating them. */
    public static Set<String> acceptedVegWords() {
        Set<String> all = new HashSet<>(VEG);
        all.addAll(NON_VEG);
        return all;
    }
}
