package org.nrg.containers.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.mockito.Mockito;
import org.nrg.containers.api.ContainerControlApi;
import org.nrg.containers.rest.LaunchRestApi;
import org.nrg.containers.services.*;
import org.nrg.framework.services.ContextService;
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
import org.springframework.scheduling.concurrent.ThreadPoolExecutorFactoryBean;
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
                                       final DockerServerService mockDockerServerService,
                                       final UserManagementServiceI mockUserManagementServiceI,
                                       final RoleHolder roleHolder,
                                       final ObjectMapper mapper,
                                       final ThreadPoolExecutorFactoryBean threadPoolExecutorFactoryBean) {
        return new LaunchRestApi(commandService, containerService, commandResolutionService,
                mockDockerServerService, mockUserManagementServiceI, roleHolder, mapper, threadPoolExecutorFactoryBean);
    }

    @Bean
    public CommandResolutionService commandResolutionService() {
        return Mockito.mock(CommandResolutionService.class);
    }

    @Bean
    public ContainerService mockContainerService() {
        return Mockito.mock(ContainerService.class);
    }

    @Bean
    public CommandService mockCommandService() {
        return Mockito.mock(CommandService.class);
    }

    @Bean
    public DockerServerService mockDockerServerService() {
        return Mockito.mock(DockerServerService.class);
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
