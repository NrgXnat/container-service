package org.nrg.containers.config;

import java.util.concurrent.TimeUnit;

import javax.jms.ConnectionFactory;
import javax.jms.Destination;
import javax.jms.JMSException;

import lombok.extern.slf4j.Slf4j;
import org.apache.activemq.command.ActiveMQQueue;
import org.nrg.containers.events.DockerStatusUpdater;
import org.nrg.containers.jms.errors.ContainerJmsErrorHandler;
import org.nrg.containers.jms.preferences.QueuePrefsBean;
import org.nrg.containers.jms.tasks.QueueManager;
import org.nrg.framework.annotations.XnatPlugin;
import org.nrg.mail.services.MailService;
import org.nrg.xdat.preferences.SiteConfigPreferences;
import org.nrg.xnat.initialization.RootConfig;
import org.nrg.xnat.services.XnatAppInfo;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.*;
import org.springframework.context.annotation.ComponentScan.Filter;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;
import org.springframework.jms.annotation.EnableJms;
import org.springframework.jms.config.DefaultJmsListenerContainerFactory;
import org.springframework.scheduling.concurrent.ThreadPoolExecutorFactoryBean;
import org.springframework.scheduling.config.TriggerTask;
import org.springframework.scheduling.support.PeriodicTrigger;

import com.fasterxml.jackson.databind.Module;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.guava.GuavaModule;

@Slf4j
@EnableJms
@Configuration
@XnatPlugin(value = "containers",
        name = "containers",
        description = "Container Service",
        entityPackages = "org.nrg.containers",
        logConfigurationFile = "META-INF/resources/containers-logback.xml",
        version = ""
)
@ComponentScan(value = "org.nrg.containers",
        excludeFilters = @Filter(type = FilterType.REGEX, pattern = ".*TestConfig.*", value = {}))
@Import({RootConfig.class})
public class ContainersConfig {
    public static final String QUEUE_MIN_CONCURRENCY_DFLT = "10";
    public static final String QUEUE_MAX_CONCURRENCY_DFLT = "20";

    private DefaultJmsListenerContainerFactory defaultFactory(ConnectionFactory connectionFactory,
                                                              final SiteConfigPreferences siteConfigPreferences,
                                                              final MailService mailService) {
        DefaultJmsListenerContainerFactory factory = new DefaultJmsListenerContainerFactory();
        factory.setConnectionFactory(connectionFactory);
        factory.setConcurrency(QUEUE_MIN_CONCURRENCY_DFLT + "-" + QUEUE_MAX_CONCURRENCY_DFLT);
        factory.setErrorHandler(new ContainerJmsErrorHandler(siteConfigPreferences, mailService));
        return factory;
    }

    @Bean(name = {"finalizingQueueListenerFactory", "jmsListenerContainerFactory"})
    public DefaultJmsListenerContainerFactory finalizingQueueListenerFactory(final SiteConfigPreferences siteConfigPreferences,
                                                                             final MailService mailService,
                                                                             @Qualifier("springConnectionFactory")
                                                                                           ConnectionFactory connectionFactory) {
        return defaultFactory(connectionFactory, siteConfigPreferences, mailService);
    }

    @Bean(name = "stagingQueueListenerFactory")
    public DefaultJmsListenerContainerFactory stagingQueueListenerFactory(final SiteConfigPreferences siteConfigPreferences,
                                                                          final MailService mailService,
                                                                          @Qualifier("springConnectionFactory")
                                                                                       ConnectionFactory connectionFactory) {
        return defaultFactory(connectionFactory, siteConfigPreferences, mailService);
    }

    @Bean
    public TriggerTask refreshQueueListenerConcurrencies(final XnatAppInfo xnatAppInfo,
                                                         final QueuePrefsBean queuePrefsBean) {
        // Shadow servers will need to periodically refresh their prefs beans from the db to pick up any
        // API changes made on the tomcat server
        final Runnable updatePrefsFromDb = queuePrefsBean.getRefresher(xnatAppInfo.isPrimaryNode());
        return new TriggerTask(
                updatePrefsFromDb,
                new PeriodicTrigger(10L, TimeUnit.SECONDS)
        );
    }

    @Bean(name = "containerStagingRequest")
    public Destination containerStagingRequest(@Value("containerStagingRequest") String containerStagingRequest)
            throws JMSException {
        return new ActiveMQQueue(containerStagingRequest);
    }

	@Bean(name = "containerFinalizingRequest")
	public Destination containerFinalizingRequest(@Value("containerFinalizingRequest") String containerFinalizingRequest)
            throws JMSException {
		return new ActiveMQQueue(containerFinalizingRequest);
	}
	
	@Bean    
	public Module guavaModule() {
        return new GuavaModule();
    }
	

    @Bean
    public ObjectMapper objectMapper(final Jackson2ObjectMapperBuilder objectMapperBuilder) {
        return objectMapperBuilder.build();
    }

    @Bean
    public TriggerTask dockerEventPullerTask(final DockerStatusUpdater dockerStatusUpdater) {
        return new TriggerTask(
                dockerStatusUpdater,
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
}

