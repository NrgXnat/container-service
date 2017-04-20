package org.nrg.containers.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.mockito.Mockito;
import org.nrg.containers.api.ContainerControlApi;
import org.nrg.containers.model.server.docker.DockerServerPrefsBean;
import org.nrg.containers.rest.LaunchRestApi;
import org.nrg.containers.services.CommandResolutionService;
import org.nrg.containers.services.CommandService;
import org.nrg.containers.services.ContainerEntityService;
import org.nrg.containers.services.ContainerService;
import org.nrg.containers.services.impl.ContainerServiceImpl;
import org.nrg.framework.services.ContextService;
import org.nrg.transporter.TransportService;
import org.nrg.transporter.TransportServiceImpl;
import org.nrg.xdat.preferences.SiteConfigPreferences;
import org.nrg.xdat.security.services.PermissionsServiceI;
import org.nrg.xdat.security.services.RoleHolder;
import org.nrg.xdat.security.services.UserManagementServiceI;
import org.nrg.xdat.services.AliasTokenService;
import org.nrg.xnat.services.archive.CatalogService;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.security.authentication.TestingAuthenticationProvider;
import org.springframework.security.config.annotation.authentication.builders.AuthenticationManagerBuilder;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configuration.WebSecurityConfigurerAdapter;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

@Configuration
@EnableWebMvc
@EnableWebSecurity
@Import({RestApiTestConfig.class, ObjectMapperConfig.class})
public class LaunchRestApiTestConfig extends WebSecurityConfigurerAdapter {
    @Bean
    public LaunchRestApi launchRestApi(final CommandService commandService,
                                       final ContainerService containerService,
                                       final CommandResolutionService commandResolutionService,
                                       final UserManagementServiceI userManagementServiceI,
                                       final RoleHolder roleHolder) {
        return new LaunchRestApi(commandService, containerService, commandResolutionService, userManagementServiceI, roleHolder);
    }

    @Bean
    public ContainerService containerService(final CommandService commandService,
                                             final ContainerControlApi containerControlApi,
                                             final ContainerEntityService containerEntityService,
                                             final CommandResolutionService commandResolutionService,
                                             final AliasTokenService aliasTokenService,
                                             final SiteConfigPreferences siteConfigPreferences,
                                             final TransportService transportService,
                                             final PermissionsServiceI permissionsService,
                                             final CatalogService catalogService,
                                             final ObjectMapper mapper) {
        final ContainerService containerService =
                new ContainerServiceImpl(commandService, containerControlApi, containerEntityService,
                        commandResolutionService, aliasTokenService, siteConfigPreferences,
                        transportService, catalogService, mapper);
        ((ContainerServiceImpl)containerService).setPermissionsService(permissionsService);
        return containerService;
    }

    @Bean
    public CommandResolutionService commandResolutionService() {
        return Mockito.mock(CommandResolutionService.class);
    }

    @Bean
    public CommandService mockCommandService() {
        return Mockito.mock(CommandService.class);
    }

    @Bean
    public DockerServerPrefsBean dockerServerPrefsBean() {
        return Mockito.mock(DockerServerPrefsBean.class);
    }

    @Bean
    public ContainerControlApi controlApi() {
        return Mockito.mock(ContainerControlApi.class);
    }

    @Bean
    public AliasTokenService aliasTokenService() {
        return Mockito.mock(AliasTokenService.class);
    }

    @Bean
    public SiteConfigPreferences siteConfigPreferences() {
        return Mockito.mock(SiteConfigPreferences.class);
    }

    @Bean
    public TransportService transportService() {
        return new TransportServiceImpl();
    }

    @Bean
    public ContainerEntityService mockContainerEntityService() {
        return Mockito.mock(ContainerEntityService.class);
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
    public ContextService contextService(final ApplicationContext applicationContext) {
        final ContextService contextService = new ContextService();
        contextService.setApplicationContext(applicationContext);
        return contextService;
    }

    @Override
    protected void configure(AuthenticationManagerBuilder auth) throws Exception {
        auth.authenticationProvider(new TestingAuthenticationProvider());
    }

}
