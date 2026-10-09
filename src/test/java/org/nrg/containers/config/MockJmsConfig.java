package org.nrg.containers.config;

import lombok.extern.slf4j.Slf4j;
import org.apache.activemq.command.ActiveMQQueue;
import org.mockito.Mockito;
import org.nrg.containers.events.listeners.ContainerEventListener;
import org.nrg.containers.events.listeners.DockerServiceEventListener;
import org.nrg.containers.events.model.ContainerEvent;
import org.nrg.containers.events.model.ServiceTaskEvent;
import org.nrg.containers.jms.errors.ContainerJmsErrorHandler;
import org.nrg.containers.jms.listeners.ContainerFinalizingRequestListener;
import org.nrg.containers.jms.listeners.ContainerStagingRequestListener;
import org.nrg.containers.jms.requests.ContainerFinalizingRequest;
import org.nrg.containers.jms.requests.ContainerStagingRequest;
import org.nrg.containers.services.ContainerService;
import org.nrg.containers.utils.StaticMocks;
import org.nrg.mail.services.MailService;
import org.nrg.xdat.preferences.NotificationsPreferences;
import org.nrg.xdat.preferences.SiteConfigPreferences;
import org.nrg.xdat.security.services.UserManagementServiceI;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;
import org.springframework.jms.core.BrowserCallback;
import org.springframework.jms.core.JmsTemplate;
import org.springframework.jms.core.MessagePostProcessor;

import javax.jms.Destination;
import java.util.concurrent.ExecutorService;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;

@Slf4j
@Configuration
public class MockJmsConfig {
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

    // Since CS-1047 service and container events also travel over JMS, so they need destinations to be routed by
    @Bean(name = ServiceTaskEvent.QUEUE)
    public Destination serviceTaskEventQueue() {
        return new ActiveMQQueue(ServiceTaskEvent.QUEUE);
    }

    @Bean(name = ContainerEvent.QUEUE)
    public Destination containerEventQueue() {
        return new ActiveMQQueue(ContainerEvent.QUEUE);
    }

    @SuppressWarnings("unchecked")
    @Bean
    public JmsTemplate mockJmsTemplate(Destination containerStagingRequest,
                                       @Lazy final ContainerStagingRequestListener containerStagingRequestListener,
                                       Destination containerFinalizingRequest,
                                       @Lazy final ContainerFinalizingRequestListener containerFinalizingRequestListener,
                                       Destination serviceTaskEventQueue,
                                       @Lazy final DockerServiceEventListener serviceEventListener,
                                       Destination containerEventQueue,
                                       @Lazy final ContainerEventListener containerEventListener,
                                       ExecutorService executorService,
                                       final SiteConfigPreferences siteConfigPreferences,
                                       final NotificationsPreferences notificationsPreferences,
                                       final MailService mailService) {
        final ContainerJmsErrorHandler errorHandler = new ContainerJmsErrorHandler(siteConfigPreferences, notificationsPreferences, mailService);
        JmsTemplate mockJmsTemplate = Mockito.mock(JmsTemplate.class);
        deliver(mockJmsTemplate, containerStagingRequest, ContainerStagingRequest.class,
                containerStagingRequestListener::onRequest, executorService, errorHandler);
        deliver(mockJmsTemplate, containerFinalizingRequest, ContainerFinalizingRequest.class,
                containerFinalizingRequestListener::onRequest, executorService, errorHandler);
        deliver(mockJmsTemplate, serviceTaskEventQueue, ServiceTaskEvent.class,
                serviceEventListener::onRequest, executorService, errorHandler);
        deliver(mockJmsTemplate, containerEventQueue, ContainerEvent.class,
                containerEventListener::onRequest, executorService, errorHandler);

        // Mock counts
        doReturn(0).when(mockJmsTemplate).browse(eq(ContainerStagingRequest.DESTINATION), (BrowserCallback<Integer>) any(BrowserCallback.class));
        doReturn(0).when(mockJmsTemplate).browse(eq(ContainerFinalizingRequest.DESTINATION), (BrowserCallback<Integer>) any(BrowserCallback.class));

        return mockJmsTemplate;
    }

    private interface Listener<T> {
        void onRequest(T request) throws Exception;
    }

    /**
     * Stands in for the broker: each message sent to the destination is handed to its listener on another thread,
     * as JMS would, with the current test's static mocks opened on that thread.
     */
    private static <T> void deliver(final JmsTemplate mockJmsTemplate, final Destination destination, final Class<T> type,
                                    final Listener<T> listener, final ExecutorService executorService,
                                    final ContainerJmsErrorHandler errorHandler) {
        doAnswer(invocation -> {
            final T request = type.cast(invocation.getArguments()[1]);
            executorService.execute(() -> {
                try {
                    StaticMocks.runWithMocks(() -> {
                        try {
                            listener.onRequest(request);
                        } catch (Exception e) {
                            errorHandler.handleError(e);
                        }
                    });
                } catch (Throwable t) {
                    // Failing to open the test's static mocks here, or an Error from the listener, would otherwise
                    // leave the test to time out with no cause shown
                    log.error("Could not deliver {} to its listener", request, t);
                }
            });
            return null;
        }).when(mockJmsTemplate).convertAndSend(eq(destination), any(type), any(MessagePostProcessor.class));
    }
}
