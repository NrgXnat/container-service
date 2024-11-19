package org.nrg.containers.config;

import org.apache.activemq.ActiveMQConnectionFactory;
import org.apache.activemq.command.ActiveMQQueue;
import org.nrg.containers.jms.errors.ContainerJmsErrorHandler;
import org.nrg.containers.jms.listeners.ContainerFinalizingRequestListener;
import org.nrg.containers.jms.listeners.ContainerStagingRequestListener;
import org.nrg.containers.jms.requests.ContainerFinalizingRequest;
import org.nrg.containers.jms.requests.ContainerStagingRequest;
import org.nrg.containers.services.ContainerService;
import org.nrg.mail.services.MailService;
import org.nrg.xdat.preferences.SiteConfigPreferences;
import org.nrg.xdat.security.services.UserManagementServiceI;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jms.annotation.EnableJms;
import org.springframework.jms.config.DefaultJmsListenerContainerFactory;
import org.springframework.jms.connection.CachingConnectionFactory;
import org.springframework.jms.core.JmsTemplate;

import javax.jms.ConnectionFactory;
import javax.jms.Destination;

@Configuration
@EnableJms
public class JmsConfig {
    @Bean
    public ContainerStagingRequestListener containerStagingRequestListener(ContainerService containerService,
                                                                           UserManagementServiceI mockUserManagementServiceI) {
        return new ContainerStagingRequestListener(containerService, mockUserManagementServiceI);
    }

    @Bean(name = ContainerStagingRequest.DESTINATION)
    public Destination containerStagingRequest() {
        return new ActiveMQQueue(ContainerStagingRequest.DESTINATION);
    }

    @Bean
    public ContainerFinalizingRequestListener containerFinalizingRequestListener(ContainerService containerService,
                                                                                 UserManagementServiceI mockUserManagementServiceI) {
        return new ContainerFinalizingRequestListener(containerService, mockUserManagementServiceI);
    }

    @Bean(name = ContainerFinalizingRequest.DESTINATION)
    public Destination containerFinalizingRequest() {
        return new ActiveMQQueue(ContainerFinalizingRequest.DESTINATION);
    }

    @Bean(name = "eventHandlingRequest")
    public Destination eventHandlingRequest() {
        return new ActiveMQQueue("eventHandlingRequest");
    }

    @Bean(name = "finalizingQueueListenerFactory")
    public DefaultJmsListenerContainerFactory finalizingQueueListenerFactory(final SiteConfigPreferences siteConfigPreferences,
                                                                             final MailService mockMailService,
                                                                             final ConnectionFactory connectionFactory) {
        return defaultFactory(connectionFactory, siteConfigPreferences, mockMailService);
    }

    @Bean(name = "stagingQueueListenerFactory")
    public DefaultJmsListenerContainerFactory stagingQueueListenerFactory(final SiteConfigPreferences siteConfigPreferences,
                                                                          final MailService mockMailService,
                                                                          final ConnectionFactory connectionFactory) {
        return defaultFactory(connectionFactory, siteConfigPreferences, mockMailService);
    }

    @Bean(name = "eventHandlingQueueListenerFactory")
    public DefaultJmsListenerContainerFactory eventHandlingQueueListenerFactory(final SiteConfigPreferences siteConfigPreferences,
                                                                                final MailService mailService,
                                                                                final ConnectionFactory connectionFactory) {
        return defaultFactory(connectionFactory, siteConfigPreferences, mailService);
    }

    @Bean
    public JmsTemplate jmsTemplate(ConnectionFactory connectionFactory){
        return new JmsTemplate(connectionFactory);
    }

    @Bean
    public ConnectionFactory connectionFactory() {
        ActiveMQConnectionFactory mq = new ActiveMQConnectionFactory("vm://localhost?broker.persistent=false");
        mq.setTrustAllPackages(true);
        return new CachingConnectionFactory(mq);
    }

    private DefaultJmsListenerContainerFactory defaultFactory(ConnectionFactory connectionFactory,
                                                              final SiteConfigPreferences siteConfigPreferences,
                                                              final MailService mailService) {
        DefaultJmsListenerContainerFactory factory = new DefaultJmsListenerContainerFactory();
        factory.setConnectionFactory(connectionFactory);
        factory.setErrorHandler(new ContainerJmsErrorHandler(siteConfigPreferences, mailService));
        return factory;
    }
}
