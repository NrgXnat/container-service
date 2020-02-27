package org.nrg.containers.rest;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.Sets;
import com.jayway.jsonpath.Configuration;
import com.jayway.jsonpath.Option;
import com.jayway.jsonpath.spi.json.JacksonJsonProvider;
import com.jayway.jsonpath.spi.json.JsonProvider;
import com.jayway.jsonpath.spi.mapper.JacksonMappingProvider;
import com.jayway.jsonpath.spi.mapper.MappingProvider;
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
import org.powermock.reflect.Whitebox;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jms.config.DefaultJmsListenerContainerFactory;
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

import java.io.File;
import java.util.*;

import static org.hamcrest.Matchers.*;
import static org.junit.Assert.assertThat;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@RunWith(SpringJUnit4ClassRunner.class)
@WebAppConfiguration
@ContextConfiguration(classes = QueueSettingsRestApiTestConfig.class)
public class QueueSettingsRestApiTest {
    private UserI mockAdmin;
    private Authentication authentication;
    private MockMvc mockMvc;

    public static final String TOOL_ID = "jms-queue"; //Match QueuePrefsBean toolId
    private static final String MIN_FINALIZING = "concurrencyMinFinalizingQueue";
    private static final String MAX_FINALIZING = "concurrencyMaxFinalizingQueue";
    private static final String MIN_STAGING = "concurrencyMinStagingQueue";
    private static final String MAX_STAGING = "concurrencyMaxStagingQueue";
    public static final Map<String, Object> PREF_MAP = new HashMap<String, Object>() {{
        put(MIN_FINALIZING, Integer.parseInt(ContainersConfig.QUEUE_MIN_CONCURRENCY_DFLT));
        put(MAX_FINALIZING, Integer.parseInt(ContainersConfig.QUEUE_MAX_CONCURRENCY_DFLT));
        put(MIN_STAGING, Integer.parseInt(ContainersConfig.QUEUE_MIN_CONCURRENCY_DFLT));
        put(MAX_STAGING, Integer.parseInt(ContainersConfig.QUEUE_MAX_CONCURRENCY_DFLT));
    }};

    private final String PATH = "/jms_queues";

    private final MediaType JSON = MediaType.APPLICATION_JSON_UTF8;

    private String VALID_MIN = "2";
    private String VALID_MAX = "300";
    private String VALID_MIN_ALT = "1";
    private String VALID_MAX_ALT = "1";

    private String INVALID_MIN = "50";
    private String INVALID_MAX = "20";

    @Autowired private WebApplicationContext wac;
    @Autowired private RoleServiceI mockRoleService;
    @Autowired private AliasTokenService mockAliasTokenService;
    @Autowired private UserManagementServiceI mockUserManagementServiceI;
    @Autowired private QueuePrefsBean queuePrefsBean;
    @Autowired private NrgPreferenceService fakePrefsService;
    @Autowired private DefaultJmsListenerContainerFactory finalizingQueueListenerFactory;
    @Autowired private DefaultJmsListenerContainerFactory stagingQueueListenerFactory;
    @Autowired private ObjectMapper mapper;

    @Rule public TemporaryFolder folder = new TemporaryFolder(new File("/tmp"));

    @Before
    public void setup() throws Exception {
        mockMvc = MockMvcBuilders.webAppContextSetup(wac).apply(springSecurity()).build();

        Configuration.setDefaults(new Configuration.Defaults() {

            private final JsonProvider jsonProvider = new JacksonJsonProvider();
            private final MappingProvider mappingProvider = new JacksonMappingProvider();

            @Override
            public JsonProvider jsonProvider() {
                return jsonProvider;
            }

            @Override
            public MappingProvider mappingProvider() {
                return mappingProvider;
            }

            @Override
            public Set<Option> options() {
                return Sets.newHashSet(Option.DEFAULT_PATH_LEAF_TO_NULL);
            }
        });

        // Mock the userI
        final String username = "fakeuser";
        final String password = "fakepass";
        mockAdmin = Mockito.mock(UserI.class);
        when(mockAdmin.getLogin()).thenReturn(username);
        when(mockAdmin.getPassword()).thenReturn(password);
        when(mockRoleService.isSiteAdmin(mockAdmin)).thenReturn(true);

        authentication = new TestingAuthenticationToken(mockAdmin, password);

        // Mock the user management service
        when(mockUserManagementServiceI.getUser(username)).thenReturn(mockAdmin);

        // Mock the aliasTokenService
        final AliasToken mockAliasToken = new AliasToken();

        final String alias = "fakealias";
        final String secret = "fakesecret";
        mockAliasToken.setAlias(alias);
        mockAliasToken.setSecret(secret);
        when(mockAliasTokenService.issueTokenForUser(mockAdmin)).thenReturn(mockAliasToken);
    }

    private void testValidSet(DefaultJmsListenerContainerFactory factory, String minParam, String maxParam) throws Exception {
        Map<String, String> params = new HashMap<>();
        params.put(minParam, VALID_MIN);
        params.put(maxParam, VALID_MAX);
        String requestJson = mapper.writeValueAsString(params);

        MockHttpServletRequestBuilder request =
                post(PATH).contentType(JSON)
                        .content(requestJson)
                        .with(authentication(authentication))
                        .with(csrf())
                        .with(testSecurityContext());

        mockMvc.perform(request).andExpect(status().isOk());

        assertThat(queuePrefsBean.getIntegerValue(minParam), is(Integer.parseInt(VALID_MIN)));
        assertThat(queuePrefsBean.getIntegerValue(maxParam), is(Integer.parseInt(VALID_MAX)));
        assertThat((String) Whitebox.getInternalState(factory, "concurrency"),
                is(VALID_MIN + "-" + VALID_MAX));

        params = new HashMap<>();
        params.put(minParam, VALID_MIN_ALT);
        params.put(maxParam, VALID_MAX_ALT);
        requestJson = mapper.writeValueAsString(params);
        request = post(PATH).contentType(JSON)
                .content(requestJson)
                .with(authentication(authentication))
                .with(csrf())
                .with(testSecurityContext());

        mockMvc.perform(request).andExpect(status().isOk());

        assertThat(queuePrefsBean.getIntegerValue(minParam), is(Integer.parseInt(VALID_MIN_ALT)));
        assertThat(queuePrefsBean.getIntegerValue(maxParam), is(Integer.parseInt(VALID_MAX_ALT)));
        assertThat((String) Whitebox.getInternalState(factory, "concurrency"),
                is(VALID_MIN_ALT + "-" + VALID_MAX_ALT));
    }

    public void testInvalidSet(DefaultJmsListenerContainerFactory factory, String minParam, String maxParam) throws Exception {
        String prevState = Whitebox.getInternalState(factory, "concurrency");
        Map<String, String> params = new HashMap<>();
        params.put(minParam, INVALID_MIN);
        params.put(maxParam, INVALID_MAX);
        String requestJson = mapper.writeValueAsString(params);
        final MockHttpServletRequestBuilder request =
                post(PATH).contentType(JSON)
                        .content(requestJson)
                        .with(authentication(authentication))
                        .with(csrf())
                        .with(testSecurityContext());

        try {
            mockMvc.perform(request).andExpect(status().isBadRequest());
        } catch (NestedServletException e) {
            if (!(e.getCause() instanceof ClientException)) {
                // we expect ClientException so ignore that, rethrow anything else
                throw e;
            }
        }

        assertThat(queuePrefsBean.getIntegerValue(minParam), is(Integer.parseInt(ContainersConfig.QUEUE_MIN_CONCURRENCY_DFLT)));
        assertThat(queuePrefsBean.getIntegerValue(maxParam), is(Integer.parseInt(ContainersConfig.QUEUE_MAX_CONCURRENCY_DFLT)));
        assertThat((String) Whitebox.getInternalState(factory, "concurrency"),
                is(prevState));
    }

    public void checkBeanValue(Map<String, Object> expectedPrefMap) throws Exception {
        checkBeanValue(expectedPrefMap, mapper.writeValueAsString(queuePrefsBean));
    }
    public void checkBeanValue(Map<String, Object> expectedPrefMap, String beanAsString) throws Exception {
        // Get the QueuePrefsBean bean as it is - without hitting the fake prefs service
        final Map<String, Object> prefs = mapper.readValue(beanAsString,
                new TypeReference<Map<String, Object>>() {});
        for (String key : expectedPrefMap.keySet()) {
            assertThat(prefs, hasKey(key));
            assertThat(prefs.get(key), is(expectedPrefMap.get(key)));
        }
    }

    @Test
    @DirtiesContext
    public void testValidSetFinalizing() throws Exception {
        testValidSet(finalizingQueueListenerFactory, MIN_FINALIZING, MAX_FINALIZING);
    }

    @Test
    @DirtiesContext
    public void testInvalidSetFinalizing() throws Exception {
        testInvalidSet(finalizingQueueListenerFactory, MIN_FINALIZING, MAX_FINALIZING);
    }

    @Test
    @DirtiesContext
    public void testValidSetStaging() throws Exception {
        testValidSet(stagingQueueListenerFactory, MIN_STAGING, MAX_STAGING);
    }

    @Test
    @DirtiesContext
    public void testInvalidSetStaging() throws Exception {
        testInvalidSet(stagingQueueListenerFactory, MIN_STAGING, MAX_STAGING);
    }

    @Test
    public void testGet() throws Exception {
        // beanAsMap will be equal to PREF_MAP, but let's just be specific - we want the GET to return the bean
        Map<String, Object> beanAsMap = mapper.readValue(mapper.writeValueAsString(queuePrefsBean),
                new TypeReference<Map<String, Object>>() {});

        final MockHttpServletRequestBuilder request = get(PATH)
                .with(authentication(authentication))
                .with(csrf())
                .with(testSecurityContext());

        final String response =
                mockMvc.perform(request)
                        .andExpect(status().isOk())
                        .andExpect(content().contentType(JSON))
                        .andReturn()
                        .getResponse()
                        .getContentAsString();


        checkBeanValue(beanAsMap, response);
    }

    @Test
    @DirtiesContext
    public void testRefresh() throws Exception  {
        // Make sure that we're in the state we expect
        checkBeanValue(PREF_MAP);

        // Fake changing the preferences in the db, as if changed via API on another node
        final Map<String, Object> altMap = new HashMap<String, Object>() {{
            put(MIN_FINALIZING, Integer.parseInt(VALID_MIN));
            put(MAX_FINALIZING, Integer.parseInt(VALID_MAX));
            put(MIN_STAGING, Integer.parseInt(VALID_MAX_ALT));
            put(MAX_STAGING, Integer.parseInt(VALID_MIN_ALT));
        }};
        for (String key : altMap.keySet()) {
            fakePrefsService.setPreferenceValue(TOOL_ID, key, ((Integer) altMap.get(key)).toString());
        }

        // Confirm that we didn't just accidentally update our bean...
        checkBeanValue(PREF_MAP);

        // Now update the bean
        final Runnable updatePrefsFromDb = queuePrefsBean.getRefresher(false);
        updatePrefsFromDb.run();

        // Make sure bean is actually updated
        checkBeanValue(altMap); // Get the QueuePrefsBean bean as it is - without hitting the prefs service

        // Make sure concurrencies are also updated
        assertThat((String) Whitebox.getInternalState(finalizingQueueListenerFactory, "concurrency"),
                is(VALID_MIN + "-" + VALID_MAX));
        assertThat((String) Whitebox.getInternalState(stagingQueueListenerFactory, "concurrency"),
                is(VALID_MIN_ALT + "-" + VALID_MAX_ALT));
    }
}
