package org.nrg.containers.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.mockito.Mockito;
import org.nrg.config.services.ConfigService;
import org.nrg.containers.services.ContainerConfigService;
import org.nrg.containers.services.OrchestrationEntityService;
import org.nrg.containers.services.OrchestrationProjectEntityService;
import org.nrg.containers.services.impl.ContainerConfigServiceImpl;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

@Configuration
@Import({ObjectMapperConfig.class})
public class CommandConfigurationTestConfig {
    @Bean
    public ConfigService configService() {
        return Mockito.mock(ConfigService.class);
    }

    @Bean
    public ContainerConfigService containerConfigService(ConfigService configService, ObjectMapper mapper) {
        return new ContainerConfigServiceImpl(configService, mapper,  Mockito.mock(OrchestrationProjectEntityService.class), Mockito.mock(OrchestrationEntityService.class));
    }
}
