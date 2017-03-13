package org.nrg.containers.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Maps;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mockito;
import org.nrg.config.entities.Configuration;
import org.nrg.config.services.ConfigService;
import org.nrg.containers.config.CommandConfigurationTestConfig;
import org.nrg.containers.model.CommandConfiguration.CommandInputConfiguration;
import org.nrg.containers.services.ContainerConfigService;
import org.nrg.framework.constants.Scope;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit4.SpringJUnit4ClassRunner;

import java.util.Map;

import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThat;
import static org.mockito.Matchers.anyString;
import static org.mockito.Matchers.eq;
import static org.mockito.Matchers.isNotNull;
import static org.mockito.Matchers.isNull;
import static org.mockito.Mockito.when;
import static org.nrg.containers.services.ContainerConfigService.TOOL_ID;

@RunWith(SpringJUnit4ClassRunner.class)
@ContextConfiguration(classes = CommandConfigurationTestConfig.class)
public class CommandConfigurationTest {

    private static final long COMMAND_ID = 12L;
    private static final String WRAPPER_NAME = "what";
    private static final String PROJECT_NAME = "xyz";

    @Autowired private ObjectMapper mapper;
    @Autowired private ContainerConfigService containerConfigService;
    @Autowired private ConfigService mockConfigService;

    @Test
    public void testSpringConfiguration() {
        assertThat(containerConfigService, not(nullValue()));
    }

    @Test
    public void testConfigureCommandForSite() throws Exception {

        final CommandConfiguration site = CommandConfiguration.create(
                ImmutableMap.of("foo", CommandInputConfiguration.builder().defaultValue("a").userSettable(true).build()),
                ImmutableMap.of("bar", CommandConfiguration.CommandOutputConfiguration.create("label")));
        final String siteJson = mapper.writeValueAsString(CommandConfigurationInternalRepresentation.create(true, site));

        final Configuration mockSiteConfiguration = Mockito.mock(Configuration.class);
        when(mockSiteConfiguration.getContents()).thenReturn(siteJson);
        when(mockConfigService.getConfig(eq(TOOL_ID), anyString(), eq(Scope.Site), isNull(String.class))).thenReturn(mockSiteConfiguration);

        final CommandConfiguration retrieved = containerConfigService.getSiteConfiguration(COMMAND_ID, WRAPPER_NAME);
        assertEquals(site, retrieved);
    }

    @Test
    public void testConfigureCommandForProject() throws Exception {

        final Map<String, CommandInputConfiguration> siteInputs = Maps.newHashMap();
        final Map<String, CommandInputConfiguration> projectInputs = Maps.newHashMap();
        final Map<String, CommandInputConfiguration> expectedInputs = Maps.newHashMap();

        final CommandInputConfiguration allNullInput = CommandInputConfiguration.builder().build();

        final CommandInputConfiguration allNotNullInput = allNotNullInputBuilder().build();
        final CommandInputConfiguration allNotNullInput2 = CommandInputConfiguration.create("fly", "fools", false, false);

        siteInputs.put("a", allNotNullInput);
        projectInputs.put("a", allNullInput);
        expectedInputs.put("a", allNotNullInput);

        siteInputs.put("e", allNullInput);
        projectInputs.put("e", allNullInput);
        expectedInputs.put("e", allNullInput);

        siteInputs.put("f", allNotNullInput);
        projectInputs.put("f", allNotNullInput2);
        expectedInputs.put("f", allNotNullInput2);

        siteInputs.put("b", allNotNullInput);
        projectInputs.put("b", CommandInputConfiguration.builder().defaultValue("not-null").build());
        expectedInputs.put("b", allNotNullInputBuilder().defaultValue("not-null").build());

        siteInputs.put("c", allNotNullInput);
        projectInputs.put("c", CommandInputConfiguration.builder().matcher("not-null").build());
        expectedInputs.put("c", allNotNullInputBuilder().matcher("not-null").build());

        siteInputs.put("d", allNotNullInput);
        projectInputs.put("d", CommandInputConfiguration.builder().userSettable(false).build());
        expectedInputs.put("d", allNotNullInputBuilder().userSettable(false).build());

        final Map<String, CommandConfiguration.CommandOutputConfiguration> siteOutputs = Maps.newHashMap();
        final Map<String, CommandConfiguration.CommandOutputConfiguration> projectOutputs = Maps.newHashMap();
        final Map<String, CommandConfiguration.CommandOutputConfiguration> expectedOutputs = Maps.newHashMap();

        final CommandConfiguration.CommandOutputConfiguration allNull = CommandConfiguration.CommandOutputConfiguration.create(null);
        final CommandConfiguration.CommandOutputConfiguration nonNull = CommandConfiguration.CommandOutputConfiguration.create("181024y2");
        final CommandConfiguration.CommandOutputConfiguration nonNull2 = CommandConfiguration.CommandOutputConfiguration.create("2");

        siteOutputs.put("a", nonNull);
        projectOutputs.put("a", allNull);
        expectedOutputs.put("a", nonNull);

        siteOutputs.put("b", allNull);
        projectOutputs.put("b", nonNull);
        expectedOutputs.put("b", nonNull);

        siteOutputs.put("c", nonNull);
        projectOutputs.put("c", nonNull2);
        expectedOutputs.put("c", nonNull2);

        final CommandConfiguration site = CommandConfiguration.create(siteInputs, siteOutputs);
        final CommandConfiguration project = CommandConfiguration.create(projectInputs, projectOutputs);
        final CommandConfiguration expected = CommandConfiguration.create(expectedInputs, expectedOutputs);

        final String siteJson = mapper.writeValueAsString(CommandConfigurationInternalRepresentation.create(true, site));
        final String projectJson = mapper.writeValueAsString(CommandConfigurationInternalRepresentation.create(true, project));

        final Configuration mockSiteConfiguration = Mockito.mock(Configuration.class);
        when(mockSiteConfiguration.getContents()).thenReturn(siteJson);
        when(mockConfigService.getConfig(eq(TOOL_ID), anyString(), eq(Scope.Site), isNull(String.class))).thenReturn(mockSiteConfiguration);
        final Configuration mockProjectConfiguration = Mockito.mock(Configuration.class);
        when(mockProjectConfiguration.getContents()).thenReturn(projectJson);
        when(mockConfigService.getConfig(eq(TOOL_ID), anyString(), eq(Scope.Project), isNotNull(String.class))).thenReturn(mockProjectConfiguration);

        final CommandConfiguration retrieved = containerConfigService.getProjectConfiguration(PROJECT_NAME, COMMAND_ID, WRAPPER_NAME);
        assertEquals(expected, retrieved);
    }

    private CommandInputConfiguration.Builder allNotNullInputBuilder() {
        return CommandInputConfiguration.builder()
                .defaultValue("who")
                .matcher("cares")
                .advanced(true)
                .userSettable(true);
    }
}