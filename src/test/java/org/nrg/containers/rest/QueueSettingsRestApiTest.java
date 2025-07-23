package org.nrg.containers.rest;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.mockito.Mockito;
import org.nrg.action.ClientException;
import org.nrg.containers.config.ContainersConfig;
import org.nrg.containers.config.QueueSettingsRestApiTestConfig;
import org.nrg.containers.jms.preferences.QueuePrefsBean;
import org.nrg.prefs.services.NrgPreferenceService;
import org.nrg.xdat.entities.AliasToken;
import org.nrg.xdat.security.services.RoleServiceI;
import org.nrg.xdat.security.services.UserManagementServiceI;
import org.nrg.xdat.services.AliasTokenService;
import org.nrg.xft.security.UserI;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jms.config.JmsListenerEndpointRegistry;
import org.springframework.jms.listener.DefaultMessageListenerContainer;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit4.SpringJUnit4ClassRunner;
import org.springframework.test.context.web.WebAppConfiguration;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.util.NestedServletException;

import javax.jms.ConnectionFactory;
import javax.jms.Destination;
import java.io.File;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.hasKey;
import static org.hamcrest.Matchers.is;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThat;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.testSecurityContext;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@RunWith(SpringJUnit4ClassRunner.class)
@WebAppConfiguration
@ContextConfiguration(classes = QueueSettingsRestApiTestConfig.class)
public class QueueSettingsRestApiTest {
    private Authentication authentication;
    private MockMvc mockMvc;

    public static final String QUEUE_PREFS_BEAN_TOOL_ID = "jms-queue"; //Match QueuePrefsBean toolId

    private static final String JMS_QUEUE_PREFERENCE_API_PATH = "/jms_queues";
    private static final String TMP_DIRECTORY = "/tmp";

    private static final String FAKE_USERNAME = "fakeuser";
    private static final String FAKE_PASSWORD = "fakepass";
    private static final String FAKE_ALIAS = "fakealias";
    private static final String FAKE_SECRET = "fakesecret";

    private static final String CONTAINER_MANAGER_ROLE_NAME = "ContainerManager";
    private static final String PRIVILEGED_USER_ROLE_NAME = "Privileged";

    private static final String VALID_MIN_STRING = "2";
    private static final int VALID_MIN_INT = Integer.parseInt(VALID_MIN_STRING);

    private static final String VALID_MAX_STRING = "300";
    private static final int VALID_MAX_INT = Integer.parseInt(VALID_MAX_STRING);

    private static final String VALID_MIN_ALT_STRING = "1";
    private static final int VALID_MIN_ALT_INT = Integer.parseInt(VALID_MIN_ALT_STRING);

    private static final String VALID_MAX_ALT_STRING = "1";
    private static final int VALID_MAX_ALT_INT = Integer.parseInt(VALID_MAX_ALT_STRING);

    private static final String INVALID_MIN_STRING = "50";
    private static final String INVALID_MAX_STRING = "20";

    private static final String MIN_FINALIZING = "concurrencyMinFinalizingQueue";
    private static final String MAX_FINALIZING = "concurrencyMaxFinalizingQueue";
    private static final String MIN_STAGING = "concurrencyMinStagingQueue";
    private static final String MAX_STAGING = "concurrencyMaxStagingQueue";

    public static final Map<String, Object> DEFAULT_CONCURRENCY_PREFERENCES = new HashMap<String, Object>() {{
        put(MIN_FINALIZING, Integer.parseInt(ContainersConfig.QUEUE_MIN_CONCURRENCY_DFLT));
        put(MAX_FINALIZING, Integer.parseInt(ContainersConfig.QUEUE_MAX_CONCURRENCY_DFLT));
        put(MIN_STAGING, Integer.parseInt(ContainersConfig.QUEUE_MIN_CONCURRENCY_DFLT));
        put(MAX_STAGING, Integer.parseInt(ContainersConfig.QUEUE_MAX_CONCURRENCY_DFLT));
    }};

    @Autowired
    private WebApplicationContext wac;
    @Autowired
    private RoleServiceI mockRoleService;
    @Autowired
    private AliasTokenService mockAliasTokenService;
    @Autowired
    private UserManagementServiceI mockUserManagementServiceI;
    @Autowired
    private QueuePrefsBean queuePrefsBean;
    @Autowired
    private NrgPreferenceService fakePrefsService;
    @Autowired
    private ObjectMapper mapper;

    @Autowired
    private JmsListenerEndpointRegistry jmsListenerEndpointRegistry;

    @Rule
    public TemporaryFolder folder = new TemporaryFolder(new File(TMP_DIRECTORY));

    @Before
    public void setup() throws Exception {
        mockMvc = MockMvcBuilders.webAppContextSetup(wac).apply(springSecurity()).build();

        // Mock the userI
        final String username = FAKE_USERNAME;
        final String password = FAKE_PASSWORD;
        UserI mockContainerManager = Mockito.mock(UserI.class);
        when(mockContainerManager.getLogin()).thenReturn(username);
        when(mockContainerManager.getPassword()).thenReturn(password);
        when(mockRoleService.checkRole(mockContainerManager, CONTAINER_MANAGER_ROLE_NAME)).thenReturn(true);
        when(mockRoleService.checkRole(mockContainerManager, PRIVILEGED_USER_ROLE_NAME)).thenReturn(true);

        authentication = new TestingAuthenticationToken(mockContainerManager, password);

        // Mock the user management service
        when(mockUserManagementServiceI.getUser(username)).thenReturn(mockContainerManager);

        // Mock the aliasTokenService
        final AliasToken mockAliasToken = new AliasToken();
        mockAliasToken.setAlias(FAKE_ALIAS);
        mockAliasToken.setSecret(FAKE_SECRET);

        when(mockAliasTokenService.issueTokenForUser(mockContainerManager)).thenReturn(mockAliasToken);

        final DefaultMessageListenerContainer finalizingContainer = Mockito.spy(newDefaultMessageListenerContainer());
        Mockito.doNothing().when(finalizingContainer).start();

        final DefaultMessageListenerContainer stagingContainer = Mockito.spy(newDefaultMessageListenerContainer());
        Mockito.doNothing().when(stagingContainer).start();

        when(jmsListenerEndpointRegistry.getListenerContainer(ContainersConfig.FINALIZING_QUEUE_CONTAINER_ID))
                .thenReturn(finalizingContainer);

        when(jmsListenerEndpointRegistry.getListenerContainer(ContainersConfig.STAGING_QUEUE_CONTAINER_ID))
                .thenReturn(stagingContainer);
    }

    private DefaultMessageListenerContainer newDefaultMessageListenerContainer() {
        final DefaultMessageListenerContainer container = new DefaultMessageListenerContainer();
        container.setConcurrency(ContainersConfig.QUEUE_MIN_CONCURRENCY_DFLT + "-" + ContainersConfig.QUEUE_MAX_CONCURRENCY_DFLT);
        container.setConnectionFactory(Mockito.mock(ConnectionFactory.class));
        container.setDestination(Mockito.mock(Destination.class));
        container.afterPropertiesSet();
        return container;
    }

    @Test
    @DirtiesContext
    public void testValidSetFinalizing() throws Exception {
        testValidSet(ContainersConfig.FINALIZING_QUEUE_CONTAINER_ID, MIN_FINALIZING, MAX_FINALIZING);
    }

    @Test
    @DirtiesContext
    public void testInvalidSetFinalizing() throws Exception {
        testInvalidSet(ContainersConfig.FINALIZING_QUEUE_CONTAINER_ID, MIN_FINALIZING, MAX_FINALIZING);
    }

    @Test
    @DirtiesContext
    public void testValidSetStaging() throws Exception {
        testValidSet(ContainersConfig.STAGING_QUEUE_CONTAINER_ID, MIN_STAGING, MAX_STAGING);
    }

    @Test
    @DirtiesContext
    public void testInvalidSetStaging() throws Exception {
        testInvalidSet(ContainersConfig.STAGING_QUEUE_CONTAINER_ID, MIN_STAGING, MAX_STAGING);
    }

    @Test
    public void testGet() throws Exception {
        // beanAsMap will be equal to PREF_MAP, but let's just be specific - we want the GET to return the bean
        final Map<String, Object> beanAsMap = mapper.readValue(mapper.writeValueAsString(queuePrefsBean),
                new TypeReference<Map<String, Object>>() {
                });

        final MockHttpServletRequestBuilder request = get(JMS_QUEUE_PREFERENCE_API_PATH)
                .with(authentication(authentication))
                .with(csrf())
                .with(testSecurityContext());

        final String response = mockMvc.perform(request)
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        checkBeanValue(beanAsMap, response);
    }

    @Test
    @DirtiesContext
    public void testRefresh() throws Exception {
        // Make sure that we're in the state we expect
        checkBeanValue(DEFAULT_CONCURRENCY_PREFERENCES);

        // Fake changing the preferences in the db, as if changed via API on another node
        final Map<String, Object> preferencesMap = new HashMap<String, Object>() {{
            put(MIN_FINALIZING, Integer.parseInt(VALID_MIN_STRING));
            put(MAX_FINALIZING, Integer.parseInt(VALID_MAX_STRING));
            put(MIN_STAGING, Integer.parseInt(VALID_MIN_ALT_STRING));
            put(MAX_STAGING, Integer.parseInt(VALID_MAX_ALT_STRING));
        }};

        for (String key : preferencesMap.keySet()) {
            fakePrefsService.setPreferenceValue(QUEUE_PREFS_BEAN_TOOL_ID, key, ((Integer) preferencesMap.get(key)).toString());
        }

        // Confirm that we didn't just accidentally update our bean...
        checkBeanValue(DEFAULT_CONCURRENCY_PREFERENCES);

        verifyListenerContainerConcurrency(ContainersConfig.FINALIZING_QUEUE_CONTAINER_ID,
                Integer.parseInt(ContainersConfig.QUEUE_MIN_CONCURRENCY_DFLT), Integer.parseInt(ContainersConfig.QUEUE_MAX_CONCURRENCY_DFLT));
        verifyListenerContainerConcurrency(ContainersConfig.STAGING_QUEUE_CONTAINER_ID,
                Integer.parseInt(ContainersConfig.QUEUE_MIN_CONCURRENCY_DFLT), Integer.parseInt(ContainersConfig.QUEUE_MAX_CONCURRENCY_DFLT));

        // Now update the bean
        queuePrefsBean.getRefresher(false).run();

        // Get the QueuePrefsBean bean as it is, without hitting the prefs service, to
        // make sure bean is actually updated
        checkBeanValue(preferencesMap);

        // Make sure listener container concurrency settings are updated
        verifyListenerContainerConcurrency(ContainersConfig.FINALIZING_QUEUE_CONTAINER_ID, VALID_MIN_INT, VALID_MAX_INT);
        verifyListenerContainerConcurrency(ContainersConfig.STAGING_QUEUE_CONTAINER_ID, VALID_MIN_ALT_INT, VALID_MAX_ALT_INT);
    }

    private void verifyListenerContainerConcurrency(final String listenerId, final int expectedMin, final int expectedMax) {
        final DefaultMessageListenerContainer container
                = (DefaultMessageListenerContainer) jmsListenerEndpointRegistry.getListenerContainer(listenerId);
        assertNotNull(container);

        // Give the container a few seconds to re-initialize
        await().atMost(5, TimeUnit.SECONDS).until(
                () -> container.getConcurrentConsumers() == expectedMin && container.getMaxConcurrentConsumers() == expectedMax);
    }

    private void postJmsPreferences(final Map<String, String> preferences) throws Exception {
        final MockHttpServletRequestBuilder request =
                post(JMS_QUEUE_PREFERENCE_API_PATH).contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(preferences))
                        .with(authentication(authentication))
                        .with(csrf())
                        .with(testSecurityContext());
        mockMvc.perform(request).andExpect(status().isOk());
    }

    private void testValidSet(final String listenerId, final String minPreferenceName, final String maxPreferenceName) throws Exception {
        final Map<String, String> initalPreferences = new HashMap<>();
        initalPreferences.put(minPreferenceName, VALID_MIN_STRING);
        initalPreferences.put(maxPreferenceName, VALID_MAX_STRING);
        postJmsPreferences(initalPreferences);

        assertThat(queuePrefsBean.getIntegerValue(minPreferenceName), is(VALID_MIN_INT));
        assertThat(queuePrefsBean.getIntegerValue(maxPreferenceName), is(VALID_MAX_INT));
        verifyListenerContainerConcurrency(listenerId, VALID_MIN_INT, VALID_MAX_INT);

        final Map<String, String> altPreferences = new HashMap<>();
        altPreferences.put(minPreferenceName, VALID_MIN_ALT_STRING);
        altPreferences.put(maxPreferenceName, VALID_MAX_ALT_STRING);
        postJmsPreferences(altPreferences);

        assertThat(queuePrefsBean.getIntegerValue(minPreferenceName), is(VALID_MIN_ALT_INT));
        assertThat(queuePrefsBean.getIntegerValue(maxPreferenceName), is(VALID_MAX_ALT_INT));
        verifyListenerContainerConcurrency(listenerId, VALID_MIN_ALT_INT, VALID_MAX_ALT_INT);
    }

    private void testInvalidSet(final String listenerId, String minParam, String maxParam) throws Exception {
        Map<String, String> initialPreferences = new HashMap<>();
        initialPreferences.put(minParam, INVALID_MIN_STRING);
        initialPreferences.put(maxParam, INVALID_MAX_STRING);
        try {
            postJmsPreferences(initialPreferences);
        } catch (NestedServletException e) {
            if (!(e.getCause() instanceof ClientException)) {
                throw e; // we expect ClientException so ignore that, rethrow anything else
            }
        }

        assertThat(queuePrefsBean.getValue(minParam), is(ContainersConfig.QUEUE_MIN_CONCURRENCY_DFLT));
        assertThat(queuePrefsBean.getValue(maxParam), is(ContainersConfig.QUEUE_MAX_CONCURRENCY_DFLT));
        verifyListenerContainerConcurrency(listenerId,
                Integer.parseInt(ContainersConfig.QUEUE_MIN_CONCURRENCY_DFLT), Integer.parseInt(ContainersConfig.QUEUE_MAX_CONCURRENCY_DFLT));
    }

    public void checkBeanValue(Map<String, Object> expectedPrefMap) throws Exception {
        checkBeanValue(expectedPrefMap, mapper.writeValueAsString(queuePrefsBean));
    }

    private void checkBeanValue(Map<String, Object> expectedPrefMap, String beanAsString) throws Exception {
        // Get the QueuePrefsBean bean as it is - without hitting the fake prefs service
        final Map<String, Object> prefs = mapper.readValue(beanAsString,
                new TypeReference<Map<String, Object>>() {
                });
        for (String key : expectedPrefMap.keySet()) {
            assertThat(prefs, hasKey(key));
            assertThat(prefs.get(key), is(expectedPrefMap.get(key)));
        }
    }
}
