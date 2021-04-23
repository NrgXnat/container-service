package org.nrg.containers.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.hibernate.SessionFactory;
import org.mockito.Mockito;
import org.nrg.config.services.ConfigService;
import org.nrg.containers.daos.DockerHubDao;
import org.nrg.containers.model.dockerhub.DockerHubEntity;
import org.nrg.containers.services.ContainerConfigService;
import org.nrg.containers.services.DockerHubService;
import org.nrg.containers.services.OrchestrationEntityService;
import org.nrg.containers.services.OrchestrationProjectEntityService;
import org.nrg.containers.services.impl.ContainerConfigServiceImpl;
import org.nrg.containers.services.impl.HibernateDockerHubService;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.orm.hibernate4.HibernateTransactionManager;
import org.springframework.orm.hibernate4.LocalSessionFactoryBean;
import org.springframework.transaction.support.ResourceTransactionManager;

import javax.sql.DataSource;
import java.util.Properties;

@Configuration
@Import({HibernateConfig.class, ObjectMapperConfig.class})
public class DockerHubEntityTestConfig {
    @Bean
    public DockerHubService dockerHubService(final ContainerConfigService containerConfigService) {
        return new HibernateDockerHubService(containerConfigService);
    }

    @Bean
    public DockerHubDao dockerHubDao() {
        return new DockerHubDao();
    }

    @Bean
    public ContainerConfigService containerConfigService(final ConfigService configService,
                                                         final ObjectMapper objectMapper) {
        return new ContainerConfigServiceImpl(configService, objectMapper, Mockito.mock(OrchestrationProjectEntityService.class), Mockito.mock(OrchestrationEntityService.class));
    }

    @Bean
    public ConfigService mockConfigService() {
        return Mockito.mock(ConfigService.class);
    }

    @Bean
    public LocalSessionFactoryBean sessionFactory(final DataSource dataSource, @Qualifier("hibernateProperties") final Properties properties) {
        final LocalSessionFactoryBean bean = new LocalSessionFactoryBean();
        bean.setDataSource(dataSource);
        bean.setHibernateProperties(properties);
        bean.setAnnotatedClasses(
                DockerHubEntity.class);
        return bean;
    }

    @Bean
    public ResourceTransactionManager transactionManager(final SessionFactory sessionFactory) throws Exception {
        return new HibernateTransactionManager(sessionFactory);
    }
}
