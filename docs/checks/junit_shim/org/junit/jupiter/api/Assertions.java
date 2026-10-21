package org.junit.jupiter.api;

import org.junit.jupiter.api.function.Executable;
import java.util.Objects;

/** A small stand-in for JUnit 5's Assertions, with the same semantics for the methods the tests use. */
public final class Assertions {
    private Assertions() {}
    private static String msg(String m) { return m == null ? "" : m + " ==> "; }
    public static void assertEquals(Object e, Object a) { assertEquals(e, a, (String) null); }
    public static void assertEquals(Object e, Object a, String m) {
        if (!Objects.equals(e, a)) throw new AssertionError(msg(m) + "expected: <" + e + "> but was: <" + a + ">");
    }
    public static void assertEquals(long e, long a) { assertEquals((Object) e, (Object) a, null); }
    public static void assertEquals(int e, int a) { assertEquals((Object) e, (Object) a, null); }
    public static void assertEquals(long e, long a, String m) { assertEquals((Object) e, (Object) a, m); }
    public static void assertEquals(int e, int a, String m) { assertEquals((Object) e, (Object) a, m); }
    // JUnit 5 also has mixed boxed/primitive overloads; without them assertEquals(4, someInteger) is ambiguous.
    public static void assertEquals(int e, Integer a) { assertEquals((Object) e, (Object) a, null); }
    public static void assertEquals(Integer e, int a) { assertEquals((Object) e, (Object) a, null); }
    public static void assertEquals(Integer e, Integer a) { assertEquals((Object) e, (Object) a, null); }
    public static void assertEquals(long e, Long a) { assertEquals((Object) e, (Object) a, null); }
    public static void assertEquals(Long e, long a) { assertEquals((Object) e, (Object) a, null); }
    public static void assertEquals(Long e, Long a) { assertEquals((Object) e, (Object) a, null); }
    public static void assertEquals(int e, Integer a, String m) { assertEquals((Object) e, (Object) a, m); }
    public static void assertEquals(Integer e, int a, String m) { assertEquals((Object) e, (Object) a, m); }
    public static void assertEquals(Integer e, Integer a, String m) { assertEquals((Object) e, (Object) a, m); }
    public static void assertEquals(long e, Long a, String m) { assertEquals((Object) e, (Object) a, m); }
    public static void assertEquals(Long e, long a, String m) { assertEquals((Object) e, (Object) a, m); }
    public static void assertEquals(Long e, Long a, String m) { assertEquals((Object) e, (Object) a, m); }
    public static void assertEquals(double e, double a, double d) { assertEquals(e, a, d, null); }
    public static void assertEquals(double e, double a, double d, String m) {
        if (!(Math.abs(e - a) <= d)) throw new AssertionError(msg(m) + "expected: <" + e + "> but was: <" + a + ">");
    }
    public static void assertNotEquals(Object u, Object a) { assertNotEquals(u, a, null); }
    public static void assertNotEquals(Object u, Object a, String m) {
        if (Objects.equals(u, a)) throw new AssertionError(msg(m) + "expected: not equal but was: <" + a + ">");
    }
    public static void assertTrue(boolean c) { assertTrue(c, null); }
    public static void assertTrue(boolean c, String m) { if (!c) throw new AssertionError(msg(m) + "expected: <true> but was: <false>"); }
    public static void assertFalse(boolean c) { assertFalse(c, null); }
    public static void assertFalse(boolean c, String m) { if (c) throw new AssertionError(msg(m) + "expected: <false> but was: <true>"); }
    public static void assertNull(Object a) { assertNull(a, null); }
    public static void assertNull(Object a, String m) { if (a != null) throw new AssertionError(msg(m) + "expected: <null> but was: <" + a + ">"); }
    public static void assertNotNull(Object a) { assertNotNull(a, null); }
    public static void assertNotNull(Object a, String m) { if (a == null) throw new AssertionError(msg(m) + "expected: not <null>"); }
    public static void assertSame(Object e, Object a) { if (e != a) throw new AssertionError("expected same instance"); }
    public static void fail(String m) { throw new AssertionError(m); }
    public static <T extends Throwable> T assertThrows(Class<T> type, Executable ex) { return assertThrows(type, ex, (String) null); }
    public static <T extends Throwable> T assertThrows(Class<T> type, Executable ex, String m) {
        try { ex.execute(); }
        catch (Throwable t) {
            if (type.isInstance(t)) return type.cast(t);
            throw new AssertionError(msg(m) + "expected " + type.getSimpleName() + " but " + t.getClass().getName() + " was thrown: " + t.getMessage(), t);
        }
        throw new AssertionError(msg(m) + "expected " + type.getSimpleName() + " to be thrown, but nothing was thrown");
    }
    public static void assertDoesNotThrow(Executable ex) { assertDoesNotThrow(ex, (String) null); }
    public static void assertDoesNotThrow(Executable ex, String m) {
        try { ex.execute(); } catch (Throwable t) { throw new AssertionError(msg(m) + "unexpected exception: " + t, t); }
    }
    public static <T> T assertDoesNotThrow(org.junit.jupiter.api.function.ThrowingSupplier<T> s) { return assertDoesNotThrow(s, (String) null); }
    public static <T> T assertDoesNotThrow(org.junit.jupiter.api.function.ThrowingSupplier<T> s, String m) {
        try { return s.get(); } catch (Throwable t) { throw new AssertionError(msg(m) + "unexpected exception: " + t, t); }
    }
}
