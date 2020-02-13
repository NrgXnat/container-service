package org.nrg.containers.config;

import org.apache.activemq.ActiveMQConnectionFactory;
import org.apache.activemq.command.ActiveMQQueue;
import org.mockito.Mockito;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.stubbing.Answer;
import org.nrg.containers.jms.errors.ContainerJmsErrorHandler;
import org.nrg.containers.jms.listeners.ContainerFinalizingRequestListener;
import org.nrg.containers.jms.listeners.ContainerStagingRequestListener;
import org.nrg.containers.jms.requests.ContainerFinalizingRequest;
import org.nrg.containers.jms.requests.ContainerStagingRequest;
import org.nrg.containers.services.ContainerService;
import org.nrg.mail.services.MailService;
import org.nrg.xdat.preferences.SiteConfigPreferences;
import org.nrg.xdat.security.services.UserManagementServiceI;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jms.annotation.EnableJms;
import org.springframework.jms.config.DefaultJmsListenerContainerFactory;
import org.springframework.jms.connection.CachingConnectionFactory;
import org.springframework.jms.core.BrowserCallback;
import org.springframework.jms.core.JmsTemplate;

import javax.jms.ConnectionFactory;
import javax.jms.Destination;
import javax.jms.JMSException;

import static org.mockito.Matchers.any;
import static org.mockito.Matchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;

@Configuration
@EnableJms
public class JmsConfig {
    @Bean
    public ContainerStagingRequestListener containerStagingRequestListener(ContainerService containerService,
                                                                           UserManagementServiceI mockUserManagementServiceI) {
        return new ContainerStagingRequestListener(containerService, mockUserManagementServiceI);
    }

    @Bean(name = "containerStagingRequest")
    public Destination containerStagingRequest(@Value("containerStagingRequest") String containerStagingRequest) throws JMSException {
        return new ActiveMQQueue(containerStagingRequest);
    }

    @Bean
    public ContainerFinalizingRequestListener containerFinalizingRequestListener(ContainerService containerService,
                                                                                 UserManagementServiceI mockUserManagementServiceI) {
        return new ContainerFinalizingRequestListener(containerService, mockUserManagementServiceI);
    }

    @Bean(name = "containerFinalizingRequest")
    public Destination containerFinalizingRequest(@Value("containerFinalizingRequest") String containerFinalizingRequest) throws JMSException {
        return new ActiveMQQueue(containerFinalizingRequest);
    }

    private DefaultJmsListenerContainerFactory defaultFactory(ConnectionFactory connectionFactory,
                                                              final SiteConfigPreferences siteConfigPreferences,
                                                              final MailService mailService) {
        DefaultJmsListenerContainerFactory factory = new DefaultJmsListenerContainerFactory();
        factory.setConnectionFactory(connectionFactory);
        factory.setErrorHandler(new ContainerJmsErrorHandler(siteConfigPreferences, mailService));
        return factory;
    }

    @Bean(name = {"finalizingQueueListenerFactory", "jmsListenerContainerFactory"})
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
}
