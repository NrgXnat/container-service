package org.nrg.containers.services;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.RandomStringUtils;
import org.hamcrest.TypeSafeMatcher;
import org.junit.AssumptionViolatedException;
import org.junit.Before;
import org.junit.Ignore;
import org.junit.Rule;
import org.junit.Test;
import org.junit.experimental.runners.Enclosed;
import org.junit.rules.TemporaryFolder;
import org.junit.rules.TestRule;
import org.junit.rules.TestWatcher;
import org.junit.runner.Description;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.junit.runners.Parameterized;
import org.junit.runners.Parameterized.Parameter;
import org.junit.runners.Parameterized.Parameters;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;
import org.nrg.containers.api.ContainerControlApi;
import org.nrg.containers.api.LogType;
import org.nrg.containers.model.container.auto.Container;
import org.nrg.containers.model.container.entity.ContainerEntity;
import org.nrg.containers.rest.ContainerLogPollResponse;
import org.nrg.containers.services.impl.ContainerServiceImpl;
import org.nrg.containers.utils.ContainerUtils;
import org.nrg.framework.services.NrgEventServiceI;
import org.nrg.xdat.preferences.SiteConfigPreferences;
import org.nrg.xdat.services.AliasTokenService;
import org.nrg.xnat.services.XnatAppInfo;
import org.nrg.xnat.services.archive.CatalogService;
import org.springframework.scheduling.concurrent.ThreadPoolExecutorFactoryBean;

import java.io.File;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.concurrent.ThreadLocalRandom;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.mockito.Matchers.any;
import static org.mockito.Matchers.eq;
import static org.mockito.Mockito.when;

@Slf4j
@RunWith(Enclosed.class)
public class ContainerLogTest {
    @Ignore
    @RunWith(JUnit4.class)
    public static class Base {

        @Rule
        public TestRule watcher = new TestWatcher() {
            protected void starting(Description description) {
                log.info("BEGINNING TEST " + description.getMethodName());
            }

            protected void finished(Description description) {
                log.info("ENDING TEST " + description.getMethodName());
            }
        };

        @Rule public MockitoRule rule = MockitoJUnit.rule();

        @Rule public TemporaryFolder temp = new TemporaryFolder(new File(System.getProperty("java.io.tmpdir")));

        @Mock public ContainerControlApi containerControlApi;
        @Mock public ContainerEntityService containerEntityService;
        @Mock public CommandResolutionService commandResolutionService;
        @Mock public CommandService commandService;
        @Mock public AliasTokenService aliasTokenService;
        @Mock public SiteConfigPreferences siteConfigPreferences;
        @Mock public ContainerFinalizeService containerFinalizeService;
        @Mock public XnatAppInfo xnatAppInfo;
        @Mock public CatalogService catalogService;
        @Mock public OrchestrationService orchestrationService;
        @Mock public NrgEventServiceI eventService;
        @Mock public ThreadPoolExecutorFactoryBean executorFactoryBean;

        public final ObjectMapper mapper = new ObjectMapper();

        public ContainerService containerService;

        @Before
        public void setup() {
            containerService = new ContainerServiceImpl(containerControlApi,
                    containerEntityService,
                    commandResolutionService,
                    commandService,
                    aliasTokenService,
                    siteConfigPreferences,
                    containerFinalizeService,
                    xnatAppInfo,
                    catalogService,
                    orchestrationService,
                    eventService,
                    mapper,
                    executorFactoryBean);
        }
    }

    @RunWith(JUnit4.class)
    public static class NotParameterizedLogTest extends Base {
        @Test
        public void testGetLogStreamFromFile() throws Exception {
            // setup
            final LogType logType = LogType.STDOUT;
            final File logFile = temp.newFile(logType.logName());
            final String logContents = RandomStringUtils.randomAscii(100, 1000);
            final byte[] logContentsBytes = logContents.getBytes(Charset.defaultCharset());
            Files.write(logFile.toPath(), logContentsBytes);

            final ContainerLogPollResponse expected = ContainerLogPollResponse.fromFile(logContents);

            // Mock container
            final String containerId = RandomStringUtils.randomAlphanumeric(10);
            final Container container = Container.builder()
                    .commandId(ThreadLocalRandom.current().nextLong())
                    .wrapperId(ThreadLocalRandom.current().nextLong())
                    .userId(RandomStringUtils.randomAlphabetic(5))
                    .containerId(containerId)
                    .dockerImage(RandomStringUtils.randomAlphabetic(10))
                    .commandLine(RandomStringUtils.randomAscii(10))
                    .status(ContainerUtils.TerminalState.COMPLETE.value)
                    .addLogPath(logFile.getAbsolutePath())
                    .build();
            final ContainerEntity containerEntity = ContainerEntity.fromPojo(container);
            when(containerEntityService.get(containerId)).thenReturn(containerEntity);

            // call method under test
            final ContainerLogPollResponse response = containerService.getLog(containerId, logType, (OffsetDateTime) null);

            // make assertions
            assertThat(response, is(expected));
        }
    }

    @RunWith(Parameterized.class)
    public static class ParameterizedLogTest extends Base {
        @Parameter public DateTimeFormatter formatter;

        @Parameters(name="{index} formatter={0}")
        public static Object[] data() {
            return new Object[] {DateTimeFormatter.ISO_INSTANT, DateTimeFormatter.ISO_DATE_TIME.withZone(ZoneId.systemDefault())};
        }

        @Test
        public void testGetLogsFromLiveContainer() throws Exception {

            // setup
            final LogType logType = LogType.STDOUT;
            final boolean appendTimestamps = true;

            // Mock container
            final String containerId = RandomStringUtils.randomAlphanumeric(10);
            final Container container = Container.builder()
                    .commandId(ThreadLocalRandom.current().nextLong())
                    .wrapperId(ThreadLocalRandom.current().nextLong())
                    .userId(RandomStringUtils.randomAlphabetic(5))
                    .containerId(containerId)
                    .dockerImage(RandomStringUtils.randomAlphabetic(10))
                    .commandLine(RandomStringUtils.randomAscii(10))
                    .status(RandomStringUtils.randomAlphabetic(5))  // Exact value doesn't matter so long as it is not one of ContainerUtils.TerminalState
                    .build();
            final ContainerEntity containerEntity = ContainerEntity.fromPojo(container);
            when(containerEntityService.get(containerId)).thenReturn(containerEntity);

            // Fake container log messages and timestamps
            final int logLineNumChars = 80;
            final int logInterval = 50;
            final OffsetDateTime dt1 = OffsetDateTime.now();
            final OffsetDateTime dt2 = dt1.plus(logInterval, ChronoUnit.SECONDS);
            final OffsetDateTime dt3 = dt2.plus(logInterval, ChronoUnit.SECONDS);
            final String timestamp1 = formatter.format(dt1);
            final String timestamp2 = formatter.format(dt2);
            final String timestamp3 = formatter.format(dt3);
            final OffsetDateTime shifted2 = dt2.plus(1L, ChronoUnit.SECONDS);
            final OffsetDateTime shifted3 = dt3.plus(1L, ChronoUnit.SECONDS);
            final String expectedTimestamp2 = ContainerServiceImpl.formatTimestamp(shifted2);
            final String expectedTimestamp3 = ContainerServiceImpl.formatTimestamp(shifted3);
            final String message1 = "Message " + RandomStringUtils.randomAscii(logLineNumChars);
            final String message2 = "It's a log message " + RandomStringUtils.randomAscii(logLineNumChars);
            final String message3 = "Here comes your log message " + RandomStringUtils.randomAscii(logLineNumChars);

            when(containerControlApi.getLog(eq(container), eq(logType), eq(appendTimestamps), any()))
                    .thenReturn(timestamp1 + " " + message1 + "\n" + timestamp2 + " " + message2)  // First response has two lines
                    .thenReturn(timestamp3 + " " + message3);  // Second response has one line

            final ContainerLogPollResponse expected1 = ContainerLogPollResponse.fromLive(message1 + "\n" + message2, expectedTimestamp2);
            final ContainerLogPollResponse expected2 = ContainerLogPollResponse.fromLive(message3, expectedTimestamp3);

            // Call method under test multiple times over a little while to collect "actual" logs
            final ContainerLogPollResponse actual1 = containerService.getLog(containerId, logType, (String) null);
            assertThat(actual1, is(expected1));

            final ContainerLogPollResponse actual2 = containerService.getLog(containerId, logType, actual1.getTimestamp());
            assertThat(actual2, is(expected2));
        }

        @Test
        public void testParseTimestamp() {
            final OffsetDateTime now = OffsetDateTime.now();
            log.info("Now: {}", now);

            final String timestamp = formatter.format(now);
            log.info("Now timestamp: {}", timestamp);

            final OffsetDateTime actual = ContainerServiceImpl.parseTimestamp(timestamp)
                    .orElseThrow(() -> new AssumptionViolatedException("Should be able to parse timestamp"));
            log.info("Actual: {}", actual);

            assertThat(actual, new TypeSafeMatcher<OffsetDateTime>() {
                @Override
                protected boolean matchesSafely(final OffsetDateTime item) {
                    return item.isEqual(now);
                }

                @Override
                public void describeTo(final org.hamcrest.Description description) {}
            });
        }

    }
}
