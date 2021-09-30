package org.nrg.containers.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.mockito.Mockito;
import org.nrg.config.services.ConfigService;
import org.nrg.containers.daos.CommandEntityRepository;
import org.nrg.containers.daos.OrchestrationEntityDao;
import org.nrg.containers.daos.OrchestrationProjectEntityDao;
import org.nrg.containers.services.*;
import org.nrg.containers.services.impl.*;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

@Configuration
@Import({ObjectMapperConfig.class, HibernateConfig.class})
public class CommandConfig {
    @Bean
    public CommandService commandService(final CommandEntityService commandEntityService,
                                         final ContainerConfigService containerConfigService) {
        return new CommandServiceImpl(commandEntityService, containerConfigService);
    }

    @Bean
    public CommandEntityService commandEntityService(OrchestrationEntityService orchestrationEntityService) {
        return new HibernateCommandEntityService(orchestrationEntityService);
    }

    @Bean
    public CommandEntityRepository commandEntityRepository() {
        return new CommandEntityRepository();
    }

    @Bean
    public ContainerConfigService containerConfigService(ConfigService configService, ObjectMapper mapper,
                                                         final OrchestrationProjectEntityService orchestrationProjectEntityService,
                                                         final OrchestrationEntityService orchestrationEntityService) {
        return new ContainerConfigServiceImpl(configService, mapper, orchestrationProjectEntityService, orchestrationEntityService);
    }


    @Bean
    public OrchestrationEntityService orchestrationEntityService(final OrchestrationProjectEntityService orchestrationProjectEntityService) {
        return new OrchestrationEntityServiceImpl(orchestrationProjectEntityService);
    }

    @Bean
    public OrchestrationProjectEntityService orchestrationProjectEntityService() {
        return new OrchestrationProjectEntityServiceImpl();
    }

    @Bean
    public OrchestrationEntityDao orchestrationEntityDao() {
        return new OrchestrationEntityDao();
    }

    @Bean
    public OrchestrationProjectEntityDao orchestrationProjectEntityDao() {
        return new OrchestrationProjectEntityDao();
    }

    @Bean
    public ConfigService configService() {
        return Mockito.mock(ConfigService.class);
    }
}
