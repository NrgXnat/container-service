package org.nrg.containers.config;

import org.apache.activemq.command.ActiveMQQueue;
import org.mockito.Mockito;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.stubbing.Answer;
import org.nrg.containers.jms.listeners.ContainerFinalizingRequestListener;
import org.nrg.containers.jms.listeners.ContainerStagingRequestListener;
import org.nrg.containers.jms.requests.ContainerFinalizingRequest;
import org.nrg.containers.jms.requests.ContainerStagingRequest;
import org.nrg.containers.services.ContainerService;
import org.nrg.xdat.security.services.UserManagementServiceI;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jms.core.BrowserCallback;
import org.springframework.jms.core.JmsTemplate;

import javax.jms.Destination;
import javax.jms.JMSException;

import static org.mockito.Matchers.any;
import static org.mockito.Matchers.eq;
import static org.mockito.Mockito.*;

@Configuration
public class JmsConfig {
    //TODO rewrite to actually use JMS instead of mocking it?
//    @Bean
//    public DefaultJmsListenerContainerFactory containerListenerContainerFactory(ConnectionFactory connectionFactory) {
//        DefaultJmsListenerContainerFactory factory = new DefaultJmsListenerContainerFactory();
//        factory.setConnectionFactory(connectionFactory);
//        factory.setConcurrency("10-20");
//        return factory;
//    }
//
//    @Bean
//    public JmsTemplate jmsTemplate(ConnectionFactory connectionFactory){
//        return new JmsTemplate(connectionFactory);
//    }
//
//    @Bean
//    public ConnectionFactory connectionFactory() {
//        return new CachingConnectionFactory(
//                new ActiveMQConnectionFactory("vm://localhost?broker.persistent=false")
//        );
//    }

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

    @Bean
    public JmsTemplate mockJmsTemplate(Destination containerStagingRequest,
                                   final ContainerStagingRequestListener containerStagingRequestListener,
                                   Destination containerFinalizingRequest,
                                   final ContainerFinalizingRequestListener containerFinalizingRequestListener) {
        JmsTemplate mockJmsTemplate = Mockito.mock(JmsTemplate.class);
        doAnswer(
                new Answer() {
                    public Object answer(InvocationOnMock invocation) {
                        Object[] args = invocation.getArguments();
                        ContainerStagingRequest request = (ContainerStagingRequest) args[1];
                        try {
                            containerStagingRequestListener.onRequest(request);
                        } catch (Exception e) {
                            return false;
                        }
                        return true;
                    }
                }
        ).when(mockJmsTemplate).convertAndSend(eq(containerStagingRequest), any(ContainerStagingRequest.class));

        doAnswer(
                new Answer() {
                    public Object answer(InvocationOnMock invocation) {
                        Object[] args = invocation.getArguments();
                        ContainerFinalizingRequest request = (ContainerFinalizingRequest) args[1];
                        try {
                            containerFinalizingRequestListener.onRequest(request);
                        } catch (Exception e) {
                            return false;
                        }
                        return true;
                    }
                }
        ).when(mockJmsTemplate).convertAndSend(eq(containerFinalizingRequest), any(ContainerFinalizingRequest.class));

        // Mock counts
        doReturn(0).when(mockJmsTemplate).browse(eq("containerStagingRequest"), (BrowserCallback<Integer>) any(BrowserCallback.class));
        doReturn(0).when(mockJmsTemplate).browse(eq("containerFinalizingRequest"), (BrowserCallback<Integer>) any(BrowserCallback.class));

        return mockJmsTemplate;
    }
}
