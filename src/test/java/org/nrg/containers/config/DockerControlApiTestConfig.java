package org.nrg.containers.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.mockito.Mockito;
import org.nrg.containers.api.DockerControlApi;
import org.nrg.containers.daos.ContainerEntityRepository;
import org.nrg.containers.model.server.docker.DockerServerPrefsBean;
import org.nrg.containers.services.ContainerEntityService;
import org.nrg.framework.services.NrgEventService;
import org.nrg.prefs.beans.AbstractPreferenceBean;
import org.nrg.prefs.entities.Tool;
import org.nrg.prefs.services.NrgPreferenceService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

import java.util.Collections;

import static org.mockito.Mockito.when;

@Configuration
@Import({ObjectMapperConfig.class})
public class DockerControlApiTestConfig {
    @Bean
    public DockerControlApi dockerControlApi(final DockerServerPrefsBean containerServerPref,
                                             final ObjectMapper objectMapper,
                                             final NrgEventService eventService) {
        return new DockerControlApi(containerServerPref, objectMapper, eventService);
    }

    @Bean
    public DockerServerPrefsBean dockerServerPrefsBean(final NrgPreferenceService nrgPreferenceService) {
        return new DockerServerPrefsBean(nrgPreferenceService);
    }

    @Bean
    public NrgPreferenceService mockPrefsService() {
        final NrgPreferenceService mockPrefsService = Mockito.mock(NrgPreferenceService.class);
        final Tool tool = Mockito.mock(Tool.class);
        when(tool.getToolId()).thenReturn("docker-server");
        when(mockPrefsService.getToolIds()).thenReturn(Collections.singleton("docker-server"));
        when(mockPrefsService.createTool(Mockito.any(AbstractPreferenceBean.class))).thenReturn(tool);
        when(mockPrefsService.getTool("docker-server")).thenReturn(tool);
        return mockPrefsService;
    }

    @Bean
    public ContainerEntityService mockContainerEntityService() {
        return Mockito.mock(ContainerEntityService.class);
    }

    @Bean
    public ContainerEntityRepository mockContainerExecutionRepository() {
        return Mockito.mock(ContainerEntityRepository.class);
    }

    @Bean
    public NrgEventService mockNrgEventService() {
        return Mockito.mock(NrgEventService.class);
    }
}
