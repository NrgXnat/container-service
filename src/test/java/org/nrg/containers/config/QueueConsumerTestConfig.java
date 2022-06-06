package org.nrg.containers.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.hibernate.SessionFactory;
import org.mockito.Mockito;
import org.nrg.config.services.ConfigService;
import org.nrg.containers.api.ContainerControlApi;
import org.nrg.containers.api.DockerControlApi;
import org.nrg.containers.daos.ContainerEntityRepository;
import org.nrg.containers.daos.DockerServerEntityRepository;
import org.nrg.containers.events.listeners.DockerContainerEventListener;
import org.nrg.containers.events.listeners.DockerServiceEventListener;
import org.nrg.containers.model.command.entity.CommandEntity;
import org.nrg.containers.model.command.entity.CommandInputEntity;
import org.nrg.containers.model.command.entity.CommandMountEntity;
import org.nrg.containers.model.command.entity.CommandOutputEntity;
import org.nrg.containers.model.command.entity.CommandWrapperDerivedInputEntity;
import org.nrg.containers.model.command.entity.CommandWrapperEntity;
import org.nrg.containers.model.command.entity.CommandWrapperExternalInputEntity;
import org.nrg.containers.model.command.entity.CommandWrapperOutputEntity;
import org.nrg.containers.model.command.entity.DockerCommandEntity;
import org.nrg.containers.model.command.entity.DockerSetupCommandEntity;
import org.nrg.containers.model.command.entity.DockerWrapupCommandEntity;
import org.nrg.containers.model.container.entity.ContainerEntity;
import org.nrg.containers.model.container.entity.ContainerEntityHistory;
import org.nrg.containers.model.container.entity.ContainerEntityInput;
import org.nrg.containers.model.container.entity.ContainerEntityMount;
import org.nrg.containers.model.container.entity.ContainerEntityOutput;
import org.nrg.containers.model.container.entity.ContainerMountFilesEntity;
import org.nrg.containers.model.orchestration.entity.OrchestratedWrapperEntity;
import org.nrg.containers.model.orchestration.entity.OrchestrationEntity;
import org.nrg.containers.model.orchestration.entity.OrchestrationProjectEntity;
import org.nrg.containers.model.server.docker.DockerServerEntity;
import org.nrg.containers.model.server.docker.DockerServerEntitySwarmConstraint;
import org.nrg.containers.services.CommandLabelService;
import org.nrg.containers.services.CommandResolutionService;
import org.nrg.containers.services.CommandService;
import org.nrg.containers.services.ContainerEntityService;
import org.nrg.containers.services.ContainerFinalizeService;
import org.nrg.containers.services.ContainerService;
import org.nrg.containers.services.DockerHubService;
import org.nrg.containers.services.DockerServerEntityService;
import org.nrg.containers.services.DockerServerService;
import org.nrg.containers.services.DockerService;
import org.nrg.containers.services.OrchestrationService;
import org.nrg.containers.services.impl.CommandLabelServiceImpl;
import org.nrg.containers.services.impl.ContainerFinalizeServiceImpl;
import org.nrg.containers.services.impl.ContainerServiceImpl;
import org.nrg.containers.services.impl.DockerServerServiceImpl;
import org.nrg.containers.services.impl.DockerServiceImpl;
import org.nrg.containers.services.impl.HibernateContainerEntityService;
import org.nrg.containers.services.impl.HibernateDockerServerEntityService;
import org.nrg.framework.services.ContextService;
import org.nrg.framework.services.NrgEventService;
import org.nrg.framework.services.NrgEventServiceI;
import org.nrg.mail.services.MailService;
import org.nrg.mail.services.impl.SpringBasedMailServiceImpl;
import org.nrg.xdat.preferences.SiteConfigPreferences;
import org.nrg.xdat.security.services.PermissionsServiceI;
import org.nrg.xdat.services.AliasTokenService;
import org.nrg.xnat.services.XnatAppInfo;
import org.nrg.xnat.services.archive.CatalogService;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.orm.hibernate4.HibernateTransactionManager;
import org.springframework.orm.hibernate4.LocalSessionFactoryBean;
import org.springframework.scheduling.concurrent.ThreadPoolExecutorFactoryBean;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.ResourceTransactionManager;
import reactor.Environment;
import reactor.bus.EventBus;
import reactor.core.Dispatcher;
import reactor.core.dispatch.RingBufferDispatcher;

import javax.sql.DataSource;
import java.util.Properties;

@Configuration
@EnableTransactionManagement
@Import({CommandConfig.class, HibernateConfig.class, RestApiTestConfig.class})
public class QueueConsumerTestConfig {
    /*
    Control API and dependencies + Events
     */
    @Bean
    public DockerServerService dockerServerService(final DockerServerEntityService dockerServerEntityService) {
        return new DockerServerServiceImpl(dockerServerEntityService);
    }

    @Bean
    public DockerServerEntityService dockerServerEntityService() {
        return new HibernateDockerServerEntityService();
    }

    @Bean
    public DockerServerEntityRepository dockerServerEntityRepository() {
        return new DockerServerEntityRepository();
    }

    @Bean
    public Environment env() {
        return Environment.initializeIfEmpty().assignErrorJournal();
    }

    @Bean
    public Dispatcher dispatcher() {
        return new RingBufferDispatcher("dispatch");
    }

    @Bean
    public EventBus eventBus(final Environment env, final Dispatcher dispatcher) {
        return EventBus.create(env, dispatcher);
    }

    @Bean
    public NrgEventServiceI nrgEventService(final EventBus eventBus) {
        return new NrgEventService(eventBus);
    }

    @Bean
    public DockerContainerEventListener containerEventListener(final EventBus eventBus) {
        return new DockerContainerEventListener(eventBus);
    }

    @Bean
    public DockerServiceEventListener serviceEventListener(final EventBus eventBus) {
        return new DockerServiceEventListener(eventBus);
    }

    @Bean
    public CommandLabelService commandLabelService(final ObjectMapper objectMapper) {
        return new CommandLabelServiceImpl(objectMapper);
    }

    /*
    Container launch Service and dependencies
     */
    @Bean
    public CommandResolutionService commandResolutionService() {
        return Mockito.mock(CommandResolutionService.class);
    }

    @Bean
    public CommandService mockCommandService() {
        return Mockito.mock(CommandService.class);
    }

    @Bean
    public MailService mailService() {
        return new SpringBasedMailServiceImpl(null);
    }

    @Bean
    public ContainerFinalizeService containerFinalizeService(final ContainerControlApi containerControlApi,
                                                             final SiteConfigPreferences siteConfigPreferences,
                                                             final CatalogService catalogService,
                                                             final MailService mailService) {
        return new ContainerFinalizeServiceImpl(containerControlApi, siteConfigPreferences, catalogService,mailService);
    }

    @Bean
    public ContainerService containerService(final DockerControlApi mockDockerControlApi,
                                             final ContainerEntityService mockContainerEntityService,
                                             final CommandResolutionService commandResolutionService,
                                             final CommandService mockCommandService,
                                             final AliasTokenService aliasTokenService,
                                             final SiteConfigPreferences siteConfigPreferences,
                                             final ContainerFinalizeService containerFinalizeService,
                                             @Qualifier("mockXnatAppInfo") final XnatAppInfo mockXnatAppInfo,
                                             final CatalogService catalogService,
                                             final OrchestrationService mockOrchestrationService,
                                             final NrgEventService mockNrgEventService,
                                             final ObjectMapper mapper,
                                             final ThreadPoolExecutorFactoryBean threadPoolExecutorFactoryBean) {
        return new ContainerServiceImpl(mockDockerControlApi, mockContainerEntityService,
                commandResolutionService, mockCommandService, aliasTokenService, siteConfigPreferences,
                containerFinalizeService, mockXnatAppInfo, catalogService, mockOrchestrationService,
                mockNrgEventService, mapper, threadPoolExecutorFactoryBean);
    }

    @Bean
    public DockerControlApi mockDockerControlApi(final DockerServerService dockerServerService,
                                                 final CommandLabelService commandLabelService,
                                                 final NrgEventService eventService) {
        //return Mockito.spy(new DockerControlApi(dockerServerService, commandLabelService, eventService));
        return Mockito.mock(DockerControlApi.class);
    }

    @Bean
    public ContainerEntityService mockContainerEntityService() {
        return Mockito.mock(ContainerEntityService.class);
    }

    @Bean
    public DockerService dockerService(final ContainerControlApi controlApi,
                                       final DockerHubService dockerHubService,
                                       final CommandService commandService,
                                       final DockerServerService dockerServerService,
                                       final CommandLabelService commandLabelService) {
        return new DockerServiceImpl(controlApi, dockerHubService, commandService, dockerServerService, commandLabelService);
    }

    @Bean
    public DockerHubService mockDockerHubService() {
        return Mockito.mock(DockerHubService.class);
    }

    @Bean
    public AliasTokenService aliasTokenService() {
        return Mockito.mock(AliasTokenService.class);
    }

    @Bean
    public SiteConfigPreferences siteConfigPreferences() {
        return Mockito.mock(SiteConfigPreferences.class);
    }

    @Bean
    public ConfigService configService() {
        return Mockito.mock(ConfigService.class);
    }

    @Bean
    public PermissionsServiceI permissionsService() {
        return Mockito.mock(PermissionsServiceI.class);
    }

    @Bean
    public CatalogService catalogService() {
        return Mockito.mock(CatalogService.class);
    }

    @Bean
    public OrchestrationService mockOrchestrationService() {
        return Mockito.mock(OrchestrationService.class);
    }

    @Bean
    public ContextService contextService(final ApplicationContext applicationContext) {
        final ContextService contextService = new ContextService();
        contextService.setApplicationContext(applicationContext);
        return contextService;
    }

    /*
    Container entity service and dependencies
     */
    @Bean
    public ContainerEntityService containerEntityService() {
        return new HibernateContainerEntityService();
    }

    @Bean
    public ContainerEntityRepository containerEntityRepository() {
        return new ContainerEntityRepository();
    }

    /*
    Session factory
     */
    @Bean
    public LocalSessionFactoryBean sessionFactory(final DataSource dataSource, @Qualifier("hibernateProperties") final Properties properties) {
        final LocalSessionFactoryBean bean = new LocalSessionFactoryBean();
        bean.setDataSource(dataSource);
        bean.setHibernateProperties(properties);
        bean.setAnnotatedClasses(
                DockerServerEntity.class,
                DockerServerEntitySwarmConstraint.class,
                CommandEntity.class,
                DockerCommandEntity.class,
                DockerSetupCommandEntity.class,
                DockerWrapupCommandEntity.class,
                CommandInputEntity.class,
                CommandOutputEntity.class,
                CommandMountEntity.class,
                CommandWrapperEntity.class,
                CommandWrapperExternalInputEntity.class,
                CommandWrapperDerivedInputEntity.class,
                CommandWrapperOutputEntity.class,
                OrchestrationEntity.class,
                OrchestratedWrapperEntity.class,
                OrchestrationProjectEntity.class,
                ContainerEntity.class,
                ContainerEntityHistory.class,
                ContainerEntityInput.class,
                ContainerEntityOutput.class,
                ContainerEntityMount.class,
                ContainerMountFilesEntity.class);

        return bean;
    }

    @Bean
    public ResourceTransactionManager transactionManager(final SessionFactory sessionFactory) throws Exception {
        return new HibernateTransactionManager(sessionFactory);
    }
}
