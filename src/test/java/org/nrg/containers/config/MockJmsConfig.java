package org.nrg.containers.config;

import org.apache.activemq.command.ActiveMQQueue;
import org.mockito.Mockito;
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
import org.springframework.jms.core.MessagePostProcessor;

import javax.jms.Destination;
import javax.jms.JMSException;
import java.util.concurrent.ExecutorService;

import static org.mockito.Matchers.any;
import static org.mockito.Matchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;

@Configuration
public class MockJmsConfig {
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
                                       final ContainerFinalizingRequestListener containerFinalizingRequestListener,
                                       ExecutorService executorService) {
        JmsTemplate mockJmsTemplate = Mockito.mock(JmsTemplate.class);
        doAnswer(
                invocation -> {
                    Object[] args = invocation.getArguments();
                    ContainerStagingRequest request = (ContainerStagingRequest) args[1];
                    executorService.submit(() -> {
                        try {
                            containerStagingRequestListener.onRequest(request);
                        } catch (Exception e) {
                            // ignored
                        }
                    });
                    return null;
                }
        ).when(mockJmsTemplate).convertAndSend(eq(containerStagingRequest), any(ContainerStagingRequest.class), any(MessagePostProcessor.class));

        doAnswer(
                invocation -> {
                    Object[] args = invocation.getArguments();
                    ContainerFinalizingRequest request = (ContainerFinalizingRequest) args[1];
                    executorService.submit(() -> {
                        try {
                            containerFinalizingRequestListener.onRequest(request);
                        } catch (Exception e) {
                            // ignored
                        }
                    });
                    return null;
                }
        ).when(mockJmsTemplate).convertAndSend(eq(containerFinalizingRequest), any(ContainerFinalizingRequest.class), any(MessagePostProcessor.class));

        // Mock counts
        doReturn(0).when(mockJmsTemplate).browse(eq("containerStagingRequest"), (BrowserCallback<Integer>) any(BrowserCallback.class));
        doReturn(0).when(mockJmsTemplate).browse(eq("containerFinalizingRequest"), (BrowserCallback<Integer>) any(BrowserCallback.class));

        return mockJmsTemplate;
    }
}
