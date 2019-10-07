package org.nrg.containers.config;

import org.mockito.Mockito;
import org.nrg.containers.api.ContainerControlApi;
import org.nrg.containers.rest.ContainerRestApi;
import org.nrg.containers.services.*;
import org.nrg.containers.services.impl.ContainerServiceImpl;
import org.nrg.framework.services.ContextService;
import org.nrg.xdat.preferences.SiteConfigPreferences;
import org.nrg.xdat.security.UserGroupServiceI;
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
@Import({ContainerEntityTestConfig.class, RestApiTestConfig.class, CommandConfig.class})
public class ContainerRestApiTestConfig extends WebSecurityConfigurerAdapter {
    @Bean
    public ContainerRestApi containerRestApi(final ContainerService containerService,
                                             final UserManagementServiceI userManagementServiceI,
                                             final RoleHolder roleHolder) {
        return new ContainerRestApi(containerService, userManagementServiceI, roleHolder);
    }

    @Bean
    public ContainerService containerService(final ContainerControlApi containerControlApi,
                                             final ContainerEntityService containerEntityService,
                                             final CommandResolutionService commandResolutionService,
                                             final CommandService commandService,
                                             final AliasTokenService aliasTokenService,
                                             final SiteConfigPreferences siteConfigPreferences,
                                             final ContainerFinalizeService containerFinalizeService,
                                             final CatalogService catalogService) {
        return new ContainerServiceImpl(containerControlApi, containerEntityService, commandResolutionService,
                commandService, aliasTokenService, siteConfigPreferences, containerFinalizeService,
                null, catalogService);
    }

    @Bean
    public CommandResolutionService mockCommandResolutionService() {
        return Mockito.mock(CommandResolutionService.class);
    }

    @Bean
    public AliasTokenService mockAliasTokenService() {
        return Mockito.mock(AliasTokenService.class);
    }

    @Bean
    public ContainerFinalizeService mockContainerFinalizeService() {
        return Mockito.mock(ContainerFinalizeService.class);
    }

    @Bean
    public ContextService contextService(final ApplicationContext applicationContext) {
        final ContextService contextService = new ContextService();
        contextService.setApplicationContext(applicationContext);
        return contextService;
    }

    @Bean
    public PermissionsServiceI permissionsService() {
        return Mockito.mock(PermissionsServiceI.class);
    }

    @Bean
    public UserGroupServiceI mockUserGroupService() {
        return Mockito.mock(UserGroupServiceI.class);
    }

    @Override
    protected void configure(AuthenticationManagerBuilder auth) throws Exception {
        auth.authenticationProvider(new TestingAuthenticationProvider());
    }

}
