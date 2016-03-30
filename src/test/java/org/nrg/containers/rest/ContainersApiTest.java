package org.nrg.containers.rest;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.nrg.containers.config.RestApiTestConfig;
import org.nrg.containers.exceptions.NoServerPrefException;
import org.nrg.containers.exceptions.NotFoundException;
import org.nrg.containers.model.Container;
import org.nrg.containers.model.ContainerServer;
import org.nrg.containers.services.ContainerService;
import org.nrg.prefs.exceptions.InvalidPreferenceName;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit4.SpringJUnit4ClassRunner;
import org.springframework.test.context.web.WebAppConfiguration;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import javax.servlet.ServletContext;
import java.util.List;
import java.util.Map;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@RunWith(SpringJUnit4ClassRunner.class)
@WebAppConfiguration
@ContextConfiguration(classes = RestApiTestConfig.class)
@SuppressWarnings("ThrowableInstanceNeverThrown")
public class ContainersApiTest {
    
    private MockMvc mockMvc;
    final ObjectMapper mapper = new ObjectMapper();

    final MediaType JSON = MediaType.APPLICATION_JSON_UTF8;
    final MediaType PLAIN_TEXT = MediaType.TEXT_PLAIN;
    
    final NotFoundException NOT_FOUND_EXCEPTION = new NotFoundException("Some cool message");
    final NoServerPrefException NO_SERVER_PREF_EXCEPTION = new NoServerPrefException("message");
    final InvalidPreferenceName INVALID_PREFERENCE_NAME = new InvalidPreferenceName("*invalid name*");

    final static String MOCK_CONTAINER_HOST = "fake://host.url";
    final static String MOCK_CONTAINER_CERT_PATH = "/path/to/file";
    final static ContainerServer MOCK_CONTAINER_SERVER =
        new ContainerServer(MOCK_CONTAINER_HOST, MOCK_CONTAINER_CERT_PATH);

    @Autowired
    private WebApplicationContext wac;

    @Autowired
    private ContainerService service;

    @Before
    public void setup() throws InvalidPreferenceName {
        mockMvc = MockMvcBuilders.webAppContextSetup(wac).build();

        reset(service); // To ensure test mock objects are isolated
    }

    @Test
    public void testWebApplicationContextSetup() {
        assertNotNull(service);
        assertNotNull(wac);

        ServletContext childServletContext = wac.getServletContext();
        assertNotNull(childServletContext);
    }

    @Test
    public void testGetContainers() throws Exception {
        final List<Container> mockContainerList = Lists.newArrayList(new Container("0", "first"), new Container("1", "second"));

        final Map<String, List<String>> blank = Maps.newHashMap();

        final String path = "/containers";
        final MockHttpServletRequestBuilder request = get(path).accept(JSON);

        when(service.getContainers(blank))
                .thenReturn(mockContainerList)                          // Happy path
                .thenThrow(NO_SERVER_PREF_EXCEPTION);    // No server pref defined

        // Happy path
        final String response =
                mockMvc.perform(request)
                        .andExpect(status().isOk())
                        .andExpect(content().contentType(JSON))
                        .andReturn()
                        .getResponse()
                        .getContentAsString();

        List<Container> responseImageList = mapper.readValue(response, new TypeReference<List<Container>>(){});
        assertThat(responseImageList, equalTo(mockContainerList));
        
        // No server pref defined
        mockMvc.perform(request)
                .andExpect(status().isFailedDependency());
    }

    @Test
    public void testGetContainersWithParams() throws Exception {
//        final List<Container> mockContainerList = ContainerMocks.FIRST_AND_SECOND;
//
//        final Map<String, List<String>> blank = Maps.newHashMap();
//
//        final String path = "/containers";
//        final MockHttpServletRequestBuilder request = get(path).accept(JSON);
//
//        when(service.getContainers(blank))
//                .thenReturn(mockContainerList)                          // Happy path
//                .thenThrow(NO_SERVER_PREF_EXCEPTION);    // No server pref defined
//
//        // Happy path
//        final String response =
//                mockMvc.perform(request)
//                        .andExpect(status().isOk())
//                        .andExpect(content().contentType(JSON))
//                        .andReturn()
//                        .getResponse()
//                        .getContentAsString();
//
//        List<Container> responseImageList = mapper.readValue(response, new TypeReference<List<Container>>(){});
//        assertThat(responseImageList, equalTo(mockContainerList));
//
//        // No server pref defined
//        mockMvc.perform(request)
//                .andExpect(status().isFailedDependency());
    }

    @Test
    public void testGetContainer() throws Exception {
        final String id = "foo";
        final Container mockContainer = new Container("foo", "Great");

        final String path = "/containers/" + id;
        final MockHttpServletRequestBuilder request = get(path).accept(JSON);

        when(service.getContainer(id))
                .thenReturn(mockContainer)                              // Happy path
                .thenThrow(NOT_FOUND_EXCEPTION)          // Not found
                .thenThrow(NO_SERVER_PREF_EXCEPTION);    // No server pref defined

        // Happy path
        final String responseById =
                mockMvc.perform(request)
                        .andExpect(status().isOk())
                        .andExpect(content().contentType(JSON))
                        .andReturn()
                        .getResponse()
                        .getContentAsString();

        final Container containerById = mapper.readValue(responseById, Container.class);
        assertThat(containerById, equalTo(mockContainer));

        // Not found
        mockMvc.perform(request)
                .andExpect(status().isNotFound());

        // No server pref defined
        mockMvc.perform(request)
                .andExpect(status().isFailedDependency());
    }

    @Test
    public void testGetContainerStatus() throws Exception {
        final String id = "foo";
        final String status = "Great";

        final String path = "/containers/" + id + "/status";
        final MockHttpServletRequestBuilder request = get(path);

        when(service.getContainerStatus(id))
                .thenReturn(status)                                     // Happy path
                .thenThrow(NO_SERVER_PREF_EXCEPTION);    // No server pref defined

        // Happy path
        final String response =
                mockMvc.perform(request)
                        .andExpect(status().isOk())
                        .andExpect(content().contentType(PLAIN_TEXT))
                        .andReturn()
                        .getResponse()
                        .getContentAsString();

        assertThat(response, equalTo(status));

        // No server pref defined
        mockMvc.perform(request)
                .andExpect(status().isFailedDependency());
    }

    @Test
    public void testVerbContainer() throws Exception {
//        final Map<String, String> verbAndStatus =
    }

    @Test
    public void testGetContainerLogs() throws Exception {
        // TODO
    }

    @Test
    public void testLaunch() throws Exception {
        final String path = "/containers/launch";
        // TODO
    }

    @Test
    public void testLaunchOn() throws Exception {

    }

    @Test
    public void testLaunchFromScript() throws Exception {
        final String scriptId = "123";
        final Map<String, String> otherArgs =
            ImmutableMap.of("arg1", "val1", "arg2", "val2");

        final String path = "/containers/launch/script/" + scriptId;

        // REQUEST 0: No "onServer" param (defaults to false)
        final MockHttpServletRequestBuilder request =
            post(path).contentType(JSON).content(mapper.writeValueAsString(otherArgs));

        when(service.launchFromScript(scriptId, otherArgs, false))
            .thenReturn("yay")
            .thenThrow(NOT_FOUND_EXCEPTION);

        final String response =
            mockMvc.perform(request)
                .andExpect(status().isOk())
                .andExpect(content().contentType(PLAIN_TEXT))
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertEquals("yay", response);

        // Not found
        mockMvc.perform(request).andExpect(status().isNotFound());
    }

    @Test
    public void testGetServer() throws Exception {

        final String path = "/containers/server";

        // REQUEST 0: No "onServer" param (defaults to false)
        final MockHttpServletRequestBuilder request =
                get(path).accept(JSON);

        when(service.getServer())
                .thenReturn(MOCK_CONTAINER_SERVER)
                .thenThrow(NO_SERVER_PREF_EXCEPTION);

        final String response =
                mockMvc.perform(request)
                        .andExpect(status().isOk())
                        .andExpect(content().contentType(JSON))
                        .andReturn()
                        .getResponse()
                        .getContentAsString();

        final ContainerServer responseServer =
            mapper.readValue(response, ContainerServer.class);
        assertThat(responseServer, equalTo(MOCK_CONTAINER_SERVER));

        // Not found
        mockMvc.perform(request).andExpect(status().isNotFound());
    }

    @Test
    public void testSetServer() throws Exception {

        final String containerServerJson =
            mapper.writeValueAsString(MOCK_CONTAINER_SERVER);

        final String path = "/containers/server";

        // REQUEST 0: No "onServer" param (defaults to false)
        final MockHttpServletRequestBuilder request =
                post(path).content(containerServerJson).contentType(JSON);

        doNothing().when(service).setServer(MOCK_CONTAINER_SERVER); // Have to use a different mocking syntax when method returns void

        mockMvc.perform(request)
                .andExpect(status().isOk());

        verify(service, times(1)).setServer(MOCK_CONTAINER_SERVER); // Method has been called once

        // Now mock out the exception
        doThrow(INVALID_PREFERENCE_NAME).when(service).setServer(MOCK_CONTAINER_SERVER);

        mockMvc.perform(request).andExpect(status().isInternalServerError());
        verify(service, times(2)).setServer(MOCK_CONTAINER_SERVER);
    }

    @Test
    public void testGetHub() throws Exception {
        // TODO
    }

    @Test
    public void testGetHubs() throws Exception {
        // TODO
    }

    @Test
    public void testSetHub() throws Exception {
        // TODO
    }

    @Test
    public void testSearch() throws Exception {
        // TODO
    }

    @Test
    public void testPullByName() throws Exception {
        // TODO
    }

    @Test
    public void testPullFromSource() throws Exception {
        // TODO
    }

    @Test
    public void testPing() throws Exception {
        final String path = "/containers/ping";
        final MockHttpServletRequestBuilder request = get(path);

        when(service.ping()).thenReturn("OK");

        mockMvc.perform(request)
            .andExpect(status().isOk())
            .andExpect(content().string(equalTo("OK")));
    }
}
