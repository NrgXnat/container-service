package org.nrg.containers.config;

import org.mockito.Mockito;
import org.nrg.automation.services.ScriptService;
import org.nrg.containers.api.ContainerControlApi;
import org.nrg.containers.metadata.service.ImageMetadataService;
import org.nrg.containers.model.ContainerServerPrefsBean;
import org.nrg.containers.model.ContainerHubPrefs;
import org.nrg.containers.services.ContainerService;
import org.nrg.containers.services.impl.DefaultContainerService;
import org.nrg.transporter.TransportService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

@Configuration
@EnableWebMvc
public class DefaultContainerServiceTestConfig {
    @Bean
    public ContainerControlApi mockContainerControlApi() {
        return Mockito.mock(ContainerControlApi.class);
    }

    @Bean
    public ImageMetadataService mockImageMetadataService() {
        return Mockito.mock(ImageMetadataService.class);
    }

    @Bean
    public ScriptService mockScriptService() {
        return Mockito.mock(ScriptService.class);
    }

    @Bean
    public ContainerServerPrefsBean mockContainerServer() {
        return Mockito.mock(ContainerServerPrefsBean.class);
    }

    @Bean
    public ContainerHubPrefs mockContainerHub() {
        return Mockito.mock(ContainerHubPrefs.class);
    }

    @Bean
    public TransportService mockTransportService() {
        return Mockito.mock(TransportService.class);
    }

    @Bean
    public ContainerService containerService() {
        return new DefaultContainerService();
    }
}
