package com.kashmirmenu.app.service.landing;

import com.kashmirmenu.app.exception.BadRequestException;

import java.time.DayOfWeek;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * A restaurant's weekly opening hours, written by the owner as plain lines and worked out here.
 *
 * <pre>
 * Mon-Fri 09:00-22:00
 * Sat,Sun 10:00-14:00, 18:00-23:30
 * Wed closed
 * </pre>
 *
 * A range whose end is not after its start runs past midnight (22:00-02:00). A day with no line is shown as
 * "Closed", but the whole text being empty means "hours not published": nothing is shown and no open/closed claim
 * is made. Pure and clock-free (the caller passes "now" in the restaurant's own time zone), so it is testable.
 */
public final class OpeningHours {

    public static final int MAX_TEXT = 700;
    private static final DateTimeFormatter HHMM = DateTimeFormatter.ofPattern("HH:mm", Locale.ENGLISH);
    private static final List<DayOfWeek> WEEK = List.of(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY,
            DayOfWeek.THURSDAY, DayOfWeek.FRIDAY, DayOfWeek.SATURDAY, DayOfWeek.SUNDAY);
    private static final List<String> SHORT = List.of("mon", "tue", "wed", "thu", "fri", "sat", "sun");

    public record Range(LocalTime start, LocalTime end) {
        boolean overnight() {
            return !end.isAfter(start);
        }

        @Override
        public String toString() {
            return start.format(HHMM) + "-" + clock(end);
        }
    }

    public record DayHours(String day, String hours) {
    }

    /** What the screen shows right now. {@code known} is false when no hours are published. */
    public record Status(boolean known, boolean open, String label) {
        static Status unknown() {
            return new Status(false, false, "");
        }
    }

    private final Map<DayOfWeek, List<Range>> days;

    private OpeningHours(Map<DayOfWeek, List<Range>> days) {
        this.days = days;
    }

    public boolean isPublished() {
        return !days.isEmpty();
    }

    /** Parses the owner's text. Blank is allowed (not published); a malformed line is refused with its number. */
    public static OpeningHours parse(String text) {
        Map<DayOfWeek, List<Range>> days = new EnumMap<>(DayOfWeek.class);
        if (text == null || text.isBlank()) {
            return new OpeningHours(days);
        }
        if (text.length() > MAX_TEXT) {
            throw new BadRequestException("The opening hours are too long.");
        }
        String[] lines = text.split("\\r?\\n");
        int number = 0;
        for (String raw : lines) {
            number++;
            String line = raw.strip();
            if (line.isEmpty()) {
                continue;
            }
            String[] parts = line.split("\\s+", 2);
            if (parts.length < 2) {
                throw bad(number, "write the days, then the hours (Mon-Fri 09:00-22:00)");
            }
            List<DayOfWeek> lineDays = parseDays(parts[0], number);
            List<Range> ranges = parseRanges(parts[1], number);
            for (DayOfWeek d : lineDays) {
                if (days.containsKey(d)) {
                    throw bad(number, SHORT.get(d.getValue() - 1) + " is already set on an earlier line");
                }
                days.put(d, ranges);
            }
        }
        return new OpeningHours(days);
    }

    /** The cleaned-up text to store: one line per day, in week order. Empty when nothing is published. */
    public String canonical() {
        StringBuilder sb = new StringBuilder();
        for (DayOfWeek d : WEEK) {
            if (!days.containsKey(d)) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append('\n');
            }
            sb.append(capitalised(d)).append(' ').append(describe(days.get(d)));
        }
        return sb.toString();
    }

    public List<DayHours> week() {
        List<DayHours> out = new ArrayList<>();
        if (!isPublished()) {
            return out;
        }
        for (DayOfWeek d : WEEK) {
            out.add(new DayHours(capitalised(d), describe(days.getOrDefault(d, List.of()))));
        }
        return out;
    }

    public Status status(LocalDateTime now) {
        if (!isPublished() || now == null) {
            return Status.unknown();
        }
        LocalTime t = now.toLocalTime();
        DayOfWeek today = now.getDayOfWeek();

        // Still open from a range that began yesterday and runs past midnight.
        for (Range r : days.getOrDefault(today.minus(1), List.of())) {
            if (r.overnight() && t.isBefore(r.end())) {
                return new Status(true, true, "Open now · closes " + clock(r.end()));
            }
        }
        for (Range r : days.getOrDefault(today, List.of())) {
            boolean inside = r.overnight() ? !t.isBefore(r.start()) : (!t.isBefore(r.start()) && t.isBefore(r.end()));
            if (inside) {
                return new Status(true, true, "Open now · closes " + clock(r.end()));
            }
        }
        // Closed: find the next opening within the coming week.
        for (int ahead = 0; ahead < 7; ahead++) {
            DayOfWeek d = today.plus(ahead);
            LocalTime best = null;
            for (Range r : days.getOrDefault(d, List.of())) {
                if (ahead == 0 && !r.start().isAfter(t)) {
                    continue;
                }
                if (best == null || r.start().isBefore(best)) {
                    best = r.start();
                }
            }
            if (best != null) {
                String when = ahead == 0 ? "today" : ahead == 1 ? "tomorrow" : capitalised(d);
                return new Status(true, false, "Closed · opens " + when + " " + best.format(HHMM));
            }
        }
        return new Status(true, false, "Closed");
    }

    // ------------------------------------------------------------------ parsing helpers

    private static List<DayOfWeek> parseDays(String spec, int line) {
        List<DayOfWeek> out = new ArrayList<>();
        for (String piece : spec.split(",")) {
            String p = piece.strip().toLowerCase(Locale.ENGLISH);
            if (p.isEmpty()) {
                throw bad(line, "the days are not written correctly");
            }
            String[] ends = p.split("-", -1);
            if (ends.length == 1) {
                out.add(day(ends[0], line));
            } else if (ends.length == 2) {
                int from = day(ends[0], line).getValue();
                int to = day(ends[1], line).getValue();
                // Mon-Fri runs forward; Fri-Mon wraps over the weekend.
                for (int i = from; ; i = i % 7 + 1) {
                    out.add(DayOfWeek.of(i));
                    if (i == to) {
                        break;
                    }
                }
            } else {
                throw bad(line, "the days are not written correctly");
            }
        }
        return out;
    }

    private static DayOfWeek day(String name, int line) {
        String key = name.length() >= 3 ? name.substring(0, 3) : name;
        int i = SHORT.indexOf(key);
        if (i < 0 || (name.length() > 3 && !fullName(i).startsWith(name))) {
            throw bad(line, "\"" + name + "\" is not a day (use Mon, Tue, Wed, Thu, Fri, Sat, Sun)");
        }
        return WEEK.get(i);
    }

    private static String fullName(int i) {
        return WEEK.get(i).name().toLowerCase(Locale.ENGLISH);
    }

    private static List<Range> parseRanges(String spec, int line) {
        String s = spec.strip().toLowerCase(Locale.ENGLISH);
        if (s.equals("closed")) {
            return List.of();
        }
        List<Range> out = new ArrayList<>();
        for (String piece : s.split("[,&]")) {
            String[] ends = piece.strip().split("\\s*-\\s*");
            if (ends.length != 2) {
                throw bad(line, "write hours like 09:00-22:00, or the word closed");
            }
            LocalTime start = time(ends[0], line);
            LocalTime end = time(ends[1], line);
            boolean untilMidnight = ends[1].strip().equals("24:00") || ends[1].strip().equals("24");
            if (start.equals(end) && !(untilMidnight && start.equals(LocalTime.MIDNIGHT))) {
                throw bad(line, "opening and closing time are the same");
            }
            out.add(new Range(start, end));
        }
        out.sort((a, b) -> a.start().compareTo(b.start()));
        return List.copyOf(out);
    }

    private static LocalTime time(String raw, int line) {
        String v = raw.strip();
        // "24:00" is how people write "until midnight". Midnight as an end time already means exactly that here.
        if (v.equals("24:00") || v.equals("24")) {
            return LocalTime.MIDNIGHT;
        }
        try {
            if (v.matches("\\d{1,2}")) {
                v = v + ":00";
            }
            if (v.matches("\\d:\\d{2}")) {
                v = "0" + v;
            }
            return LocalTime.parse(v, HHMM);
        } catch (DateTimeParseException e) {
            throw bad(line, "\"" + raw.strip() + "\" is not a 24-hour time such as 09:00 or 22:30");
        }
    }

    private static BadRequestException bad(int line, String why) {
        return new BadRequestException("Opening hours, line " + line + ": " + why + ".");
    }

    private static String describe(List<Range> ranges) {
        if (ranges.isEmpty()) {
            return "closed";
        }
        List<String> parts = new ArrayList<>();
        for (Range r : ranges) {
            parts.add(r.toString());
        }
        return String.join(", ", parts);
    }

    /** A time as shown to guests; midnight as a closing time reads 24:00, not 00:00. */
    static String clock(LocalTime t) {
        return LocalTime.MIDNIGHT.equals(t) ? "24:00" : t.format(HHMM);
    }

    private static String capitalised(DayOfWeek d) {
        String s = SHORT.get(d.getValue() - 1);
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }
}
