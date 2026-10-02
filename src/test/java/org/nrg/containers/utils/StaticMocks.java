package org.nrg.containers.utils;

import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.stubbing.Answer;
import org.nrg.containers.model.xnat.FakeWorkflow;
import org.nrg.xft.event.persist.PersistentWorkflowI;
import org.nrg.xft.event.persist.PersistentWorkflowUtils;
import org.nrg.xft.security.UserI;

import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Supplier;

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
    private final Map<Class<?>, Answer<?>> defaultAnswersByType = new HashMap<>();
    private final Deque<MockedStatic<?>> mocks = new ArrayDeque<>();
    private final List<WorkflowCreation> workflowCreations = new ArrayList<>();
    // One instance, so that every mockWorkflowCreation call asks for the same default answer
    private final Answer<Object> workflowCreationAnswer = this::answerWorkflowCreation;

    private StaticMocks() {}

    /** Mocks the class, or returns its mock if this group already has one, so later configuration can add stubs. */
    public <T> MockedStatic<T> mock(final Class<T> type) {
        return mock(type, null);
    }

    /**
     * Mocks the class with a default answer, or returns its existing mock. Fails if the existing mock was opened with a
     * different default answer, which would otherwise be silently ignored.
     */
    @SuppressWarnings("unchecked")
    public <T> MockedStatic<T> mock(final Class<T> type, final Answer<?> defaultAnswer) {
        final MockedStatic<?> existing = mocksByType.get(type);
        if (existing != null) {
            if (defaultAnswersByType.get(type) != defaultAnswer) {
                throw new IllegalStateException(type.getName() + " is already mocked in this group with a different default answer");
            }
            return (MockedStatic<T>) existing;
        }
        final MockedStatic<T> mock = defaultAnswer == null ? Mockito.mockStatic(type) : Mockito.mockStatic(type, defaultAnswer);
        mocksByType.put(type, mock);
        defaultAnswersByType.put(type, defaultAnswer);
        mocks.push(mock);
        return mock;
    }

    /** {@link #mockWorkflowCreation(Integer, UserI, Supplier)} for the default event the tests launch with. */
    public void mockWorkflowCreation(final UserI user, final Supplier<? extends PersistentWorkflowI> workflow) {
        mockWorkflowCreation(FakeWorkflow.defaultEventId, user, workflow);
    }

    /**
     * Mocks {@link PersistentWorkflowUtils} so that creating the workflow for {@code eventId} and {@code user} returns
     * {@code workflow}, with every other method real, as the PowerMock spy these tests were written with did.
     * Answered rather than stubbed with {@code when()}, which would run the real method while stubbing.
     */
    public void mockWorkflowCreation(final Integer eventId, final UserI user, final Supplier<? extends PersistentWorkflowI> workflow) {
        workflowCreations.add(new WorkflowCreation(eventId, user, workflow));
        mock(PersistentWorkflowUtils.class, workflowCreationAnswer);
    }

    @Override
    public void close() {
        while (!mocks.isEmpty()) {
            mocks.pop().closeOnDemand();
        }
        mocksByType.clear();
        defaultAnswersByType.clear();
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

    private Object answerWorkflowCreation(final InvocationOnMock invocation) throws Throwable {
        if (invocation.getMethod().getName().equals("getOrCreateWorkflowData") && invocation.getArguments().length >= 2) {
            // Newest first, so a later rule for the same event and user wins, as a later Mockito stub would
            for (int i = workflowCreations.size() - 1; i >= 0; i--) {
                final WorkflowCreation creation = workflowCreations.get(i);
                if (creation.matches(invocation.getArgument(0), invocation.getArgument(1))) {
                    return creation.workflow.get();
                }
            }
        }
        return invocation.callRealMethod();
    }

    private static final class WorkflowCreation {
        private final Integer eventId;
        private final UserI user;
        private final Supplier<? extends PersistentWorkflowI> workflow;

        private WorkflowCreation(final Integer eventId, final UserI user, final Supplier<? extends PersistentWorkflowI> workflow) {
            this.eventId = eventId;
            this.user = user;
            this.workflow = workflow;
        }

        private boolean matches(final Object eventIdArgument, final Object userArgument) {
            return Objects.equals(eventIdArgument, eventId) && userArgument == user;
        }
    }
}
