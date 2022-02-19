package org.nrg.containers.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.mockito.Mockito;
import org.nrg.containers.api.ContainerControlApi;
import org.nrg.containers.api.DockerControlApi;
import org.nrg.containers.rest.DockerRestApi;
import org.nrg.containers.services.CommandLabelService;
import org.nrg.containers.services.CommandService;
import org.nrg.containers.services.DockerHubService;
import org.nrg.containers.services.DockerServerService;
import org.nrg.containers.services.DockerService;
import org.nrg.containers.services.impl.CommandLabelServiceImpl;
import org.nrg.containers.services.impl.DockerServiceImpl;
import org.nrg.framework.services.ContextService;
import org.nrg.framework.services.NrgEventService;
import org.nrg.prefs.services.NrgPreferenceService;
import org.nrg.xdat.security.services.RoleHolder;
import org.nrg.xdat.security.services.UserManagementServiceI;
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
@Import(RestApiTestConfig.class)
public class DockerRestApiTestConfig extends WebSecurityConfigurerAdapter {
    @Bean
    public DockerRestApi dockerRestApi(final DockerService dockerService,
                                       final ObjectMapper objectMapper,
                                       final UserManagementServiceI userManagementService,
                                       final RoleHolder roleHolder) {
        return new DockerRestApi(dockerService, objectMapper, userManagementService, roleHolder);
    }

    @Bean
    public DockerService dockerService(final ContainerControlApi controlApi,
                                       final DockerHubService dockerHubService,
                                       final CommandService commandService,
                                       final DockerServerService dockerServerService,
                                       final CommandLabelService commandLabelService) {
        return new DockerServiceImpl(controlApi, dockerHubService, commandService, dockerServerService, commandLabelService);
    }

    @Bean
    public CommandLabelService commandLabelService(final ObjectMapper objectMapper) {
        return new CommandLabelServiceImpl(objectMapper);
    }

    @Bean
    public DockerHubService mockDockerHubService() {
        return Mockito.mock(DockerHubService.class);
    }

    @Bean
    public DockerServerService mockDockerServerService() {
        return Mockito.mock(DockerServerService.class);
    }

    @Bean
    public ContainerControlApi mockContainerControlApi(final DockerServerService dockerServerService,
                                                       final CommandLabelService commandLabelService,
                                                       final DockerHubService dockerHubService,
                                                       final NrgEventService eventService) {
        final ContainerControlApi controlApi = new DockerControlApi(dockerServerService, commandLabelService, dockerHubService, eventService);
        return Mockito.spy(controlApi);
    }

    @Bean
    public NrgPreferenceService nrgPreferenceService() {
        return Mockito.mock(NrgPreferenceService.class);
    }

    @Bean
    public CommandService mockCommandService() {
        return Mockito.mock(CommandService.class);
    }

    @Bean
    public NrgEventService mockNrgEventService() {
        return Mockito.mock(NrgEventService.class);
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
