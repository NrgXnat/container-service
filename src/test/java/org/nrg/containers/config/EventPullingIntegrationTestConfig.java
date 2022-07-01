package org.nrg.containers.config;

import org.nrg.containers.api.ContainerControlApi;
import org.nrg.containers.api.KubernetesClientFactory;
import org.nrg.containers.events.ContainerStatusUpdater;
import org.nrg.containers.services.ContainerService;
import org.nrg.containers.services.DockerServerService;
import org.nrg.framework.services.NrgEventServiceI;
import org.nrg.xnat.services.XnatAppInfo;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.SchedulingConfigurer;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;
import org.springframework.scheduling.config.TriggerTask;
import org.springframework.scheduling.support.PeriodicTrigger;
import org.springframework.transaction.annotation.EnableTransactionManagement;

import java.util.concurrent.TimeUnit;

@Configuration
@EnableScheduling
@EnableTransactionManagement
@Import({IntegrationTestConfig.class, MockJmsConfig.class})
public class EventPullingIntegrationTestConfig implements SchedulingConfigurer {
    @Bean
    public ContainerStatusUpdater containerStatusUpdater(final ContainerControlApi containerControlApi,
                                                         final ContainerService containerService,
                                                         final DockerServerService dockerServerService,
                                                         final NrgEventServiceI eventService,
                                                         @Qualifier("mockXnatAppInfo") final XnatAppInfo mockXnatAppInfo,
                                                         final KubernetesClientFactory kubernetesClientFactory) {
        return new ContainerStatusUpdater(
                containerControlApi, containerService, dockerServerService, eventService, mockXnatAppInfo, kubernetesClientFactory
        );
    }

    @Bean
    public TriggerTask containerStatusUpdateTask(final ContainerStatusUpdater containerStatusUpdater) {
        myTask = new TriggerTask(
                containerStatusUpdater,
                new PeriodicTrigger(250L, TimeUnit.MILLISECONDS)
        );
        return myTask;
    }

    @Bean(destroyMethod = "shutdown")
    public ThreadPoolTaskScheduler taskScheduler() {
        final ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setRemoveOnCancelPolicy(true);
        return scheduler;
    }

    @Override
    public void configureTasks(final ScheduledTaskRegistrar scheduledTaskRegistrar) {
        scheduledTaskRegistrar.setScheduler(taskScheduler());

        scheduledTaskRegistrar.addTriggerTask(myTask);
    }

    private TriggerTask myTask;
}
