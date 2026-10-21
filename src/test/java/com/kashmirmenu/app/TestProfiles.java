package com.kashmirmenu.app;

import org.springframework.test.context.ActiveProfilesResolver;

/**
 * Chooses the profile the integration tests run under: "test" (an in-memory H2, the default) or
 * "mysql-test" (a real MySQL, see the mysqlTest Gradle task), according to the km.test.profile system
 * property. The same tests run on either, which is the point: H2 is fast but cannot show lock, isolation
 * or migration problems that only MySQL has.
 */
public class TestProfiles implements ActiveProfilesResolver {

    public static final String PROPERTY = "km.test.profile";

    @Override
    public String[] resolve(Class<?> testClass) {
        return new String[]{System.getProperty(PROPERTY, "test")};
    }
}