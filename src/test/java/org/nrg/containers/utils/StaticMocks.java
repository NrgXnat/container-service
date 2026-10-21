package org.nrg.containers.utils;

import org.mockito.MockedStatic;
import org.mockito.Mockito;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;

/**
 * A group of Mockito static mocks opened and closed together, plus a way to open them on the threads that
 * integration tests hand work to.
 *
 * <p>Mockito static mocks only apply on the thread that opened them. The PowerMock mocks these tests were written
 * against applied everywhere, and the tests still rely on that: the status updater runs on its own scheduler thread
 * and {@code MockJmsConfig} runs the JMS listeners on an executor. Those hand-offs go through
 * {@link #runWithMocks(Runnable)}, which opens the current test's mocks on that thread for the duration of the task.
 */
public final class StaticMocks implements AutoCloseable {
    private static volatile Consumer<StaticMocks> currentConfiguration;
    private static final ThreadLocal<Boolean> OPEN_ON_THIS_THREAD = ThreadLocal.withInitial(() -> false);

    private final Map<Class<?>, MockedStatic<?>> mocksByType = new HashMap<>();
    private final Deque<MockedStatic<?>> mocks = new ArrayDeque<>();

    private StaticMocks() {}

    /** Mocks the class, or returns its mock if this group already has one, so later configuration can add stubs. */
    @SuppressWarnings("unchecked")
    public <T> MockedStatic<T> mock(final Class<T> type) {
        final MockedStatic<?> existing = mocksByType.get(type);
        if (existing != null) {
            return (MockedStatic<T>) existing;
        }
        final MockedStatic<T> mock = Mockito.mockStatic(type);
        mocksByType.put(type, mock);
        mocks.push(mock);
        return mock;
    }

    @Override
    public void close() {
        while (!mocks.isEmpty()) {
            mocks.pop().closeOnDemand();
        }
        mocksByType.clear();
    }

    /**
     * Opens the mocks on the calling thread, and registers the configuration so that {@link #runWithMocks(Runnable)}
     * can open the same mocks on other threads until the returned registration is closed.
     */
    public static Registration openOnEveryThread(final Consumer<StaticMocks> configuration) {
        if (currentConfiguration != null) {
            // Worker threads would otherwise go on opening the earlier test's stubs
            throw new IllegalStateException("Static mocks from an earlier test are still registered; close its registration in @After");
        }
        final StaticMocks onThisThread = open(configuration);
        OPEN_ON_THIS_THREAD.set(true);
        currentConfiguration = configuration;
        return new Registration(onThisThread);
    }

    /**
     * Runs the task with the current test's static mocks open on this thread. Runs it unmocked when no test has
     * registered mocks, and does not reopen them on a thread where they are already open.
     */
    public static void runWithMocks(final Runnable task) {
        final Consumer<StaticMocks> configuration = currentConfiguration;
        if (configuration == null || OPEN_ON_THIS_THREAD.get()) {
            task.run();
            return;
        }
        OPEN_ON_THIS_THREAD.set(true);
        try (final StaticMocks ignored = open(configuration)) {
            task.run();
        } finally {
            OPEN_ON_THIS_THREAD.set(false);
        }
    }

    public static final class Registration implements AutoCloseable {
        private final StaticMocks onRegisteringThread;
        private final Thread registeringThread = Thread.currentThread();

        private Registration(final StaticMocks onRegisteringThread) {
            this.onRegisteringThread = onRegisteringThread;
        }

        /**
         * Adds stubs to the mocks open on this thread and to those opened on other threads from now on. Must be
         * called on the thread that registered them, because a static mock can only be stubbed where it is open.
         */
        public void add(final Consumer<StaticMocks> moreConfiguration) {
            if (Thread.currentThread() != registeringThread) {
                // Such as an Awaitility condition, which runs on Awaitility's own thread
                throw new IllegalStateException("Static mocks can only be added on the thread that registered them, "
                        + registeringThread.getName() + ", not " + Thread.currentThread().getName());
            }
            moreConfiguration.accept(onRegisteringThread);
            final Consumer<StaticMocks> configuration = currentConfiguration;
            if (configuration != null) {
                currentConfiguration = configuration.andThen(moreConfiguration);
            }
        }

        @Override
        public void close() {
            currentConfiguration = null;
            OPEN_ON_THIS_THREAD.set(false);
            onRegisteringThread.close();
        }
    }

    private static StaticMocks open(final Consumer<StaticMocks> configuration) {
        final StaticMocks staticMocks = new StaticMocks();
        try {
            configuration.accept(staticMocks);
            return staticMocks;
        } catch (RuntimeException | Error e) {
            // A mock left open would make every later task on this thread fail to reopen it
            staticMocks.close();
            throw e;
        }
    }
}
