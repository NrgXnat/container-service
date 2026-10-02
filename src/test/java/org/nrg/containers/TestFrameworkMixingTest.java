package org.nrg.containers;

import org.junit.Test;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;

/**
 * The suite runs JUnit 4 tests on the JUnit Platform's vintage engine, which silently ignores JUnit 5 annotations
 * in a JUnit 4 class (and the reverse). That is how static mocks opened in {@code @BeforeEach} went unopened in
 * five integration tests without anything failing to compile. A class must use one framework's annotations only.
 */
public class TestFrameworkMixingTest {
    private static final List<String> JUNIT_4 = Arrays.asList(
            "org.junit.Test", "org.junit.Before", "org.junit.After", "org.junit.BeforeClass", "org.junit.AfterClass");
    private static final List<String> JUPITER = Arrays.asList(
            "org.junit.jupiter.api.Test", "org.junit.jupiter.api.BeforeEach", "org.junit.jupiter.api.AfterEach",
            "org.junit.jupiter.api.BeforeAll", "org.junit.jupiter.api.AfterAll",
            "org.junit.jupiter.params.ParameterizedTest");

    @Test
    public void noTestClassMixesJUnit4AndJupiterAnnotations() throws Exception {
        final Path testClasses = Paths.get(TestFrameworkMixingTest.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        final List<String> mixed;
        try (final Stream<Path> files = Files.walk(testClasses)) {
            mixed = files.filter(path -> path.toString().endsWith(".class"))
                    .map(path -> toClassName(testClasses, path))
                    .filter(TestFrameworkMixingTest::mixesFrameworks)
                    .sorted()
                    .collect(Collectors.toList());
        }
        // The fixture must be found, which proves the scan reached the compiled test classes and can detect mixing
        assertThat("Test classes using both JUnit 4 and Jupiter annotations", mixed, contains(MixedFixture.class.getName()));
    }

    /** Has no test methods, so neither engine runs it; it is the one class the scan above must find. */
    static class MixedFixture {
        @org.junit.Before
        public void junit4Setup() {}

        @org.junit.jupiter.api.BeforeEach
        void jupiterSetup() {}
    }

    private static String toClassName(final Path root, final Path classFile) {
        final String relative = root.relativize(classFile).toString();
        return relative.substring(0, relative.length() - ".class".length()).replace(classFile.getFileSystem().getSeparator(), ".");
    }

    private static boolean mixesFrameworks(final String className) {
        final Method[] methods;
        try {
            methods = Class.forName(className, false, TestFrameworkMixingTest.class.getClassLoader()).getDeclaredMethods();
        } catch (Throwable e) {
            // Classes that cannot be loaded without their optional dependencies are not test classes we can check
            return false;
        }
        return uses(methods, JUNIT_4) && uses(methods, JUPITER);
    }

    private static boolean uses(final Method[] methods, final List<String> annotationNames) {
        return Arrays.stream(methods)
                .flatMap(method -> Arrays.stream(method.getAnnotations()))
                .map(Annotation::annotationType)
                .map(Class::getName)
                .anyMatch(annotationNames::contains);
    }
}
