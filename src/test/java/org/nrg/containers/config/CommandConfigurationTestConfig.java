package org.nrg.containers.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.mockito.Mockito;
import org.nrg.config.services.ConfigService;
import org.nrg.containers.services.ContainerConfigService;
import org.nrg.containers.services.impl.ContainerConfigServiceImpl;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class CommandConfigurationTestConfig {
    @Bean
    public ObjectMapper objectMapper() {
        return new ObjectMapper();
    }

    @Bean
    public ConfigService configService() {
        return Mockito.mock(ConfigService.class);
    }

    @Bean
    public ContainerConfigService containerConfigService(final ConfigService configService,
                                                         final ObjectMapper objectMapper) {
        return new ContainerConfigServiceImpl(configService, objectMapper);
    }
}