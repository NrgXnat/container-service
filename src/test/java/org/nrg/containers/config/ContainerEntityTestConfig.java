package org.nrg.containers.config;

import org.mockito.Mockito;
import org.nrg.containers.api.ContainerControlApi;
import org.nrg.containers.daos.ContainerEntityRepository;
import org.nrg.containers.services.ContainerEntityService;
import org.nrg.containers.services.impl.HibernateContainerEntityService;
import org.nrg.framework.services.NrgEventServiceI;
import org.nrg.framework.services.SerializerService;
import org.nrg.prefs.services.NrgPreferenceService;
import org.nrg.xdat.preferences.SiteConfigPreferences;
import org.nrg.xdat.security.services.PermissionsServiceI;
import org.nrg.xnat.services.archive.CatalogService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import reactor.bus.EventBus;

@Configuration
@Import({HibernateConfig.class, ObjectMapperConfig.class})
public class ContainerEntityTestConfig {
    @Bean
    public EventBus eventBus() {
        return Mockito.mock(EventBus.class);
    }

    @Bean
    public ContainerControlApi containerControlApi() {
        return Mockito.mock(ContainerControlApi.class);
    }

    @Bean
    public SiteConfigPreferences siteConfigPreferences() {
        return Mockito.mock(SiteConfigPreferences.class);
    }

    @Bean
    public NrgEventServiceI nrgEventService() {
        return Mockito.mock(NrgEventServiceI.class);
    }

    @Bean
    public NrgPreferenceService nrgPreferenceService() {
        return Mockito.mock(NrgPreferenceService.class);
    }

    @Bean
    public SerializerService serializerService() {
        return Mockito.mock(SerializerService.class);
    }

    @Bean
    public PermissionsServiceI permissionsService() {
        return Mockito.mock(PermissionsServiceI.class);
    }

    @Bean
    public CatalogService catalogService() {
        return Mockito.mock(CatalogService.class);
    }

    @Bean
    public ContainerEntityService containerEntityService() {
        return new HibernateContainerEntityService();
    }

    @Bean
    public ContainerEntityRepository containerEntityRepository() {
        return new ContainerEntityRepository();
    }
}
