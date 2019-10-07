package org.nrg.containers.config;

import org.nrg.containers.api.DockerControlApi;
import org.nrg.containers.events.DockerStatusUpdater;
import org.nrg.containers.services.ContainerService;
import org.nrg.containers.services.DockerServerService;
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
@Import({IntegrationTestConfig.class, JmsConfig.class})
public class EventPullingIntegrationTestConfig implements SchedulingConfigurer {
    @Bean
    public DockerStatusUpdater dockerStatusUpdater(final DockerControlApi dockerControlApi,
                                                   final DockerServerService dockerServerService,
                                                   final ContainerService containerService,
                                                   @Qualifier("mockXnatAppInfo") final XnatAppInfo mockXnatAppInfo) {
        return new DockerStatusUpdater(dockerControlApi, dockerServerService, containerService, mockXnatAppInfo);
    }

    @Bean
    public TriggerTask dockerEventPullerTask(final DockerStatusUpdater dockerStatusUpdater) {
        myTask = new TriggerTask(
                dockerStatusUpdater,
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
