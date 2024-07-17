package org.nrg.containers.config;

import org.mockito.Mockito;
import org.nrg.containers.api.KubernetesClientFactoryImpl;
import org.nrg.containers.daos.DockerServerEntityRepository;
import org.nrg.containers.services.DockerServerEntityService;
import org.nrg.containers.services.DockerServerService;
import org.nrg.containers.services.impl.DockerServerServiceImpl;
import org.nrg.containers.services.impl.HibernateDockerServerEntityService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

@Configuration
@Import({HibernateConfig.class, ObjectMapperConfig.class})
public class DockerServerEntityTestConfig {
    @Bean
    public DockerServerEntityService dockerServerEntityService() {
        return new HibernateDockerServerEntityService();
    }

    @Bean
    public DockerServerService dockerServerService(final DockerServerEntityService dockerServerEntityService) {
        return new DockerServerServiceImpl(dockerServerEntityService, Mockito.mock(KubernetesClientFactoryImpl.class));
    }

    @Bean
    public DockerServerEntityRepository dockerServerEntityRepository() {
        return new DockerServerEntityRepository();
    }
}
