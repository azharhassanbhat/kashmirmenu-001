package shimrunner;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/** Runs every @Test method of the named classes, a fresh instance each, after any @BeforeEach. */
public final class Run {
    public static void main(String[] names) throws Exception {
        int passed = 0, failed = 0;
        List<String> failures = new ArrayList<>();
        for (String name : names) {
            Class<?> c = Class.forName(name);
            for (Method m : c.getDeclaredMethods()) {
                if (m.getAnnotation(org.junit.jupiter.api.Test.class) == null) continue;
                Object o;
                try {
                    var ctor = c.getDeclaredConstructor();
                    ctor.setAccessible(true);
                    o = ctor.newInstance();
                    for (Method b : c.getDeclaredMethods()) {
                        if (b.getAnnotation(org.junit.jupiter.api.BeforeEach.class) != null) { b.setAccessible(true); b.invoke(o); }
                    }
                    m.setAccessible(true);
                    m.invoke(o);
                    passed++;
                } catch (InvocationTargetException e) {
                    failed++;
                    Throwable t = e.getCause();
                    failures.add(c.getSimpleName() + "." + m.getName() + ": " + t.getClass().getSimpleName() + ": " + t.getMessage());
                } catch (Throwable t) {
                    failed++;
                    failures.add(c.getSimpleName() + "." + m.getName() + ": " + t);
                }
            }
        }
        for (String f : failures) System.out.println("FAILED " + f);
        System.out.println("RESULT " + passed + " passed, " + failed + " failed");
        System.exit(failed == 0 ? 0 : 1);
    }
}
