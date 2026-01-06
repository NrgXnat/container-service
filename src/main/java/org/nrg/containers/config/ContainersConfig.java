package org.nrg.containers.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.apache.activemq.command.ActiveMQQueue;
import org.jetbrains.annotations.NotNull;
import org.nrg.containers.events.ContainerStatusUpdater;
import org.nrg.containers.events.listeners.ContainerServiceWorkflowStatusEventListener;
import org.nrg.containers.events.model.ContainerEvent;
import org.nrg.containers.events.model.ScanArchiveEventToLaunchCommands;
import org.nrg.containers.events.model.ServiceTaskEvent;
import org.nrg.containers.events.model.SessionMergeOrArchiveEvent;
import org.nrg.containers.jms.errors.ContainerJmsErrorHandler;
import org.nrg.containers.jms.preferences.QueuePrefsBean;
import org.nrg.containers.jms.requests.ContainerFinalizingRequest;
import org.nrg.containers.jms.requests.ContainerStagingRequest;
import org.nrg.containers.jms.tasks.QueueManager;
import org.nrg.framework.annotations.XnatPlugin;
import org.nrg.framework.services.SerializerService;
import org.nrg.mail.services.MailService;
import org.nrg.xdat.preferences.NotificationsPreferences;
import org.nrg.xdat.preferences.SiteConfigPreferences;
import org.nrg.xnat.initialization.RootConfig;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.ComponentScan.Filter;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.annotation.Import;
import org.springframework.jms.annotation.EnableJms;
import org.springframework.jms.config.DefaultJmsListenerContainerFactory;
import org.springframework.jms.listener.DefaultMessageListenerContainer;
import org.springframework.scheduling.concurrent.ThreadPoolExecutorFactoryBean;
import org.springframework.scheduling.config.TriggerTask;
import org.springframework.scheduling.support.PeriodicTrigger;

import javax.jms.ConnectionFactory;
import javax.jms.Destination;
import java.util.concurrent.TimeUnit;

@Slf4j
@EnableJms
@Configuration
@XnatPlugin(value = "containers",
            name = "containers",
            description = "Container Service",
            entityPackages = "org.nrg.containers",
            logConfigurationFile = "META-INF/resources/containers-logback.xml")
@ComponentScan(value = "org.nrg.containers",
               excludeFilters = @Filter(type = FilterType.REGEX, pattern = ".*TestConfig.*"))
@Import({RootConfig.class})
public class ContainersConfig {
    public static final String QUEUE_MIN_CONCURRENCY_DFLT            = "10";
    public static final String QUEUE_MAX_CONCURRENCY_DFLT            = "20";
    public static final String FINALIZING_QUEUE_LISTENER_FACTORY     = "finalizingQueueListenerFactory";
    public static final String FINALIZING_QUEUE_CONTAINER_ID         = "finalizingListener";
    public static final String STAGING_QUEUE_LISTENER_FACTORY        = "stagingQueueListenerFactory";
    public static final String STAGING_QUEUE_CONTAINER_ID            = "stagingQueueListener";
    public static final String EVENT_HANDLING_QUEUE_LISTENER_FACTORY = "eventHandlingQueueListenerFactory";

    private static final int NUMBER_OF_IDLE_RECEIVES_PER_TASK_LIMIT = 300;
    private static final long ONE_SECOND_IN_MS = 1000L;

    @Bean(name = FINALIZING_QUEUE_LISTENER_FACTORY)
    public DefaultJmsListenerContainerFactory finalizingQueueListenerFactory(final SiteConfigPreferences siteConfigPreferences,
                                                                             final NotificationsPreferences notificationsPreferences,
                                                                             final MailService mailService,
                                                                             final QueuePrefsBean queuePrefs,
                                                                             @Qualifier("springConnectionFactory") final ConnectionFactory connectionFactory) {
        final DefaultJmsListenerContainerFactory factory = defaultFactory(connectionFactory, siteConfigPreferences, notificationsPreferences, mailService);
        factory.setConcurrency(queuePrefs.getConcurrencyMinFinalizingQueue() + "-" + queuePrefs.getConcurrencyMaxFinalizingQueue());
        return factory;
    }

    @Bean(name = STAGING_QUEUE_LISTENER_FACTORY)
    public DefaultJmsListenerContainerFactory stagingQueueListenerFactory(final SiteConfigPreferences siteConfigPreferences,
                                                                          final NotificationsPreferences notificationsPreferences,
                                                                          final MailService mailService,
                                                                          final QueuePrefsBean queuePrefs,
                                                                          @Qualifier("springConnectionFactory") final ConnectionFactory connectionFactory) {
        final DefaultJmsListenerContainerFactory factory = defaultFactory(connectionFactory, siteConfigPreferences, notificationsPreferences, mailService);
        factory.setConcurrency(queuePrefs.getConcurrencyMinStagingQueue() + "-" + queuePrefs.getConcurrencyMaxStagingQueue());
        return factory;
    }

    @Bean(name = EVENT_HANDLING_QUEUE_LISTENER_FACTORY)
    public DefaultJmsListenerContainerFactory eventHandlingQueueListenerFactory(final SiteConfigPreferences siteConfigPreferences,
                                                                                final NotificationsPreferences notificationsPreferences,
                                                                                final MailService mailService,
                                                                                @Qualifier("springConnectionFactory") final ConnectionFactory connectionFactory) {
        return defaultFactory(connectionFactory, siteConfigPreferences, notificationsPreferences, mailService);
    }

    @Bean(name = ContainerStagingRequest.DESTINATION)
    public Destination containerStagingRequest() {
        return new ActiveMQQueue(ContainerStagingRequest.DESTINATION);
    }

    @Bean(name = ContainerFinalizingRequest.DESTINATION)
    public Destination containerFinalizingRequest() {
        return new ActiveMQQueue(ContainerFinalizingRequest.DESTINATION);
    }

    @Bean(name = ContainerEvent.QUEUE)
    public Destination containerEventQueue() {
        return new ActiveMQQueue(ContainerEvent.QUEUE);
    }

    @Bean(name = ContainerServiceWorkflowStatusEventListener.QUEUE)
    public Destination containerServiceWorkflowStatusEventQueue() {
        return new ActiveMQQueue(ContainerServiceWorkflowStatusEventListener.QUEUE);
    }

    @Bean(name = ServiceTaskEvent.QUEUE)
    public Destination serviceTaskEventQueue() {
        return new ActiveMQQueue(ServiceTaskEvent.QUEUE);
    }

    @Bean(name = ScanArchiveEventToLaunchCommands.QUEUE)
    public Destination scanArchiveEventToLaunchCommandsQueue() {
        return new ActiveMQQueue(ScanArchiveEventToLaunchCommands.QUEUE);
    }

    @Bean(name = SessionMergeOrArchiveEvent.QUEUE)
    public Destination sessionMergeOrArchiveEventQueue() {
        return new ActiveMQQueue(SessionMergeOrArchiveEvent.QUEUE);
    }

    @Bean
    public ObjectMapper objectMapper(final SerializerService serializer) {
        return serializer.getObjectMapper();
    }

    @Bean
    public TriggerTask containerStatusUpdateTask(final ContainerStatusUpdater containerStatusUpdater) {
        return new TriggerTask(
                containerStatusUpdater,
                new PeriodicTrigger(10L, TimeUnit.SECONDS)
        );
    }

    @Bean
    public TriggerTask queueManagerTask(final QueueManager queueManager) {
        return new TriggerTask(
                queueManager,
                new PeriodicTrigger(15L, TimeUnit.MINUTES)
        );
    }

    @Bean(name = "containerServiceThreadPoolExecutorFactoryBean")
    public ThreadPoolExecutorFactoryBean containerServiceThreadPoolExecutorFactoryBean() {
        ThreadPoolExecutorFactoryBean tBean = new ThreadPoolExecutorFactoryBean();
        tBean.setCorePoolSize(5);
        tBean.setThreadNamePrefix("container-");
        return tBean;
    }

    private DefaultJmsListenerContainerFactory defaultFactory(final ConnectionFactory connectionFactory,
                                                              final SiteConfigPreferences siteConfigPreferences,
                                                              final NotificationsPreferences notificationsPreferences,
                                                              final MailService mailService) {
        final DefaultJmsListenerContainerFactory factory = new DefaultJmsListenerContainerFactory() {
            @NotNull
            @Override
            protected DefaultMessageListenerContainer createContainerInstance() {
                final DefaultMessageListenerContainer container = super.createContainerInstance();
                // Allows container to scale down the number of consumers after 5 minutes of
                // not receiving a message (300 * 1 second)
                container.setIdleReceivesPerTaskLimit(NUMBER_OF_IDLE_RECEIVES_PER_TASK_LIMIT);
                container.setReceiveTimeout(ONE_SECOND_IN_MS);
                return container;
            }
        };
        factory.setConnectionFactory(connectionFactory);
        factory.setConcurrency(QUEUE_MIN_CONCURRENCY_DFLT + "-" + QUEUE_MAX_CONCURRENCY_DFLT);
        factory.setErrorHandler(new ContainerJmsErrorHandler(siteConfigPreferences, notificationsPreferences, mailService));
        return factory;
    }
}
