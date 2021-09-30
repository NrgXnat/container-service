package org.nrg.containers.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.hibernate.SessionFactory;
import org.mockito.Mockito;
import org.nrg.config.daos.ConfigurationDAO;
import org.nrg.config.daos.ConfigurationDataDAO;
import org.nrg.config.entities.ConfigurationData;
import org.nrg.config.services.ConfigService;
import org.nrg.config.services.impl.DefaultConfigService;
import org.nrg.containers.api.ContainerControlApi;
import org.nrg.containers.daos.CommandEntityRepository;
import org.nrg.containers.daos.ContainerEntityRepository;
import org.nrg.containers.daos.OrchestrationEntityDao;
import org.nrg.containers.daos.OrchestrationProjectEntityDao;
import org.nrg.containers.model.command.entity.*;
import org.nrg.containers.model.container.entity.*;
import org.nrg.containers.model.orchestration.entity.OrchestratedWrapperEntity;
import org.nrg.containers.model.orchestration.entity.OrchestrationEntity;
import org.nrg.containers.model.orchestration.entity.OrchestrationProjectEntity;
import org.nrg.containers.services.*;
import org.nrg.containers.services.impl.*;
import org.nrg.framework.services.NrgEventService;
import org.nrg.prefs.services.NrgPreferenceService;
import org.nrg.xdat.preferences.SiteConfigPreferences;
import org.nrg.xdat.security.services.PermissionsServiceI;
import org.nrg.xnat.services.archive.CatalogService;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.orm.hibernate4.HibernateTransactionManager;
import org.springframework.orm.hibernate4.LocalSessionFactoryBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.ResourceTransactionManager;
import reactor.bus.EventBus;

import javax.sql.DataSource;
import java.util.Properties;

@Configuration
@EnableTransactionManagement
@Import({HibernateConfig.class, ObjectMapperConfig.class})
public class OrchestrationTestConfig {
    @Bean
    public CommandService commandService(final CommandEntityService commandEntityService,
                                         final ContainerConfigService containerConfigService) {
        return new CommandServiceImpl(commandEntityService, containerConfigService);
    }

    @Bean
    public ContainerConfigService containerConfigService(ConfigService configService, ObjectMapper mapper,
                                                         final OrchestrationProjectEntityService orchestrationProjectEntityService,
                                                         final OrchestrationEntityService orchestrationEntityService) {
        return new ContainerConfigServiceImpl(configService, mapper, orchestrationProjectEntityService, orchestrationEntityService);
    }

    @Bean
    public OrchestrationService orchestrationService(final OrchestrationEntityService orchestrationEntityService,
                                                     final CommandEntityService commandEntityService,

                                                     final ContainerConfigService containerConfigService) {
        return new OrchestrationServiceImpl(orchestrationEntityService, commandEntityService, containerConfigService);
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
    public CommandEntityService commandEntityService(OrchestrationEntityService orchestrationEntityService) {
        return new HibernateCommandEntityService(orchestrationEntityService);
    }

    @Bean
    public CommandEntityRepository commandEntityRepository() {
        return new CommandEntityRepository();
    }

    @Bean
    public ConfigService configService(final ConfigurationDAO configurationDAO,
                                       final ConfigurationDataDAO configurationDataDAO,
                                       final PlatformTransactionManager transactionManager,
                                       final JdbcTemplate jdbcTemplate) {
        return new DefaultConfigService(configurationDAO, configurationDataDAO, transactionManager, jdbcTemplate);
    }

    @Bean
    public ConfigurationDAO configurationDAO() {
        return new ConfigurationDAO();
    }

    @Bean
    public ConfigurationDataDAO configurationDataDAO() {
        return new ConfigurationDataDAO();
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
    public LocalSessionFactoryBean sessionFactory(final DataSource dataSource, @Qualifier("hibernateProperties") final Properties properties) {
        final LocalSessionFactoryBean bean = new LocalSessionFactoryBean();
        bean.setDataSource(dataSource);
        bean.setHibernateProperties(properties);
        bean.setAnnotatedClasses(
                OrchestrationEntity.class,
                OrchestratedWrapperEntity.class,
                OrchestrationProjectEntity.class,
                CommandEntity.class,
                CommandWrapperEntity.class,
                DockerCommandEntity.class,
                DockerSetupCommandEntity.class,
                DockerWrapupCommandEntity.class,
                CommandInputEntity.class,
                CommandOutputEntity.class,
                CommandMountEntity.class,
                CommandWrapperExternalInputEntity.class,
                CommandWrapperDerivedInputEntity.class,
                CommandWrapperOutputEntity.class,
                org.nrg.config.entities.Configuration.class,
                ConfigurationData.class);
        return bean;
    }

    @Bean
    public JdbcTemplate jdbcTemplate(DataSource dataSource) {
        return new JdbcTemplate(dataSource);
    }

    @Bean
    public ResourceTransactionManager transactionManager(final SessionFactory sessionFactory) throws Exception {
        return new HibernateTransactionManager(sessionFactory);
    }
}
