package org.nrg.containers.config;

import org.junit.runner.Runner;
import org.junit.runners.model.FrameworkMethod;
import org.junit.runners.model.InitializationError;
import org.junit.runners.parameterized.BlockJUnit4ClassRunnerWithParameters;
import org.junit.runners.parameterized.ParametersRunnerFactory;
import org.junit.runners.parameterized.TestWithParameters;
import org.springframework.test.context.junit4.SpringJUnit4ClassRunner;

public class SpringJUnit4ClassRunnerFactory implements ParametersRunnerFactory {
    //https://gist.github.com/bademux/5ed07e564c879994b93c
    //https://stackoverflow.com/questions/28560734/how-to-run-junit-springjunit4classrunner-with-parametrized
    //https://knowjavathings.blogspot.com/2014/02/junit4-for-both-parameterized-and.html
    // So as to use parameterized tests, we cannot also use @RunWith(SpringJUnit4ClassRunner.class), use this instead

    @Override
    public Runner createRunnerForTestWithParameters(final TestWithParameters test) throws InitializationError {
        final LocalBlockJUnit4ClassRunnerWithParameters testRunner = new LocalBlockJUnit4ClassRunnerWithParameters(test);

        return new SpringJUnit4ClassRunner(test.getTestClass().getJavaClass()) {
            @Override
            protected Object createTest() throws Exception {
                final Object testInstance = testRunner.createTest();
                getTestContextManager().prepareTestInstance(testInstance);
                return testInstance;
            }

            @Override
            protected String getName() {
                return testRunner.getRealName();
            }

            @Override
            protected String testName(FrameworkMethod method) {
                return method.getName() + testRunner.getRealName();
            }
        };
    }

    private class LocalBlockJUnit4ClassRunnerWithParameters extends BlockJUnit4ClassRunnerWithParameters {

        public LocalBlockJUnit4ClassRunnerWithParameters(final TestWithParameters test) throws InitializationError {
            super(test);
        }

        String getRealName() {
            return getName();
        }
    }
}