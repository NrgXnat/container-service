package org.nrg.containers.config;

import com.fasterxml.jackson.databind.Module;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.guava.GuavaModule;
import org.nrg.containers.events.DockerEventPuller;
import org.nrg.framework.annotations.XnatPlugin;
import org.nrg.transporter.config.TransporterConfig;
import org.nrg.xdat.security.PermissionsServiceImpl;
import org.nrg.xdat.security.helpers.Permissions;
import org.nrg.xdat.security.services.PermissionsServiceI;
import org.nrg.xnat.initialization.RootConfig;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.ComponentScan.Filter;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.annotation.Import;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;
import org.springframework.scheduling.config.TriggerTask;
import org.springframework.scheduling.support.PeriodicTrigger;

import java.util.concurrent.TimeUnit;

@Configuration
@XnatPlugin(value = "containers",
        name = "containers",
        description = "Container Service",
        entityPackages = "org.nrg.containers",
        log4jPropertiesFile = "META-INF/resources/log4j.properties",
        version = ""
)
@ComponentScan(value = "org.nrg.containers",
        excludeFilters = @Filter(type = FilterType.REGEX, pattern = ".*TestConfig.*", value = {}))
@Import({RootConfig.class, TransporterConfig.class})
public class ContainersConfig {
    @Bean
    public Module guavaModule() {
        return new GuavaModule();
    }

    @Bean
    public ObjectMapper objectMapper(final Jackson2ObjectMapperBuilder objectMapperBuilder) {
        return objectMapperBuilder.build();
    }

    @Bean
    public TriggerTask dockerEventPullerTask(final DockerEventPuller dockerEventPuller) {
        return new TriggerTask(
                dockerEventPuller,
                new PeriodicTrigger(10L, TimeUnit.SECONDS)
        );
    }
}