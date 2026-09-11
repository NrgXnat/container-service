package org.nrg.containers.config;

import org.mockito.Mockito;
import org.nrg.containers.rest.CommandRestApi;
import org.nrg.containers.services.CommandService;
import org.nrg.containers.services.DockerServerService;
import org.nrg.framework.services.ContextService;
import org.nrg.xdat.security.UserGroupServiceI;
import org.nrg.xdat.security.services.PermissionsServiceI;
import org.nrg.xdat.security.services.RoleHolder;
import org.nrg.xdat.security.services.UserManagementServiceI;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.authentication.TestingAuthenticationProvider;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

@Configuration
@EnableWebMvc
@EnableWebSecurity
@Import({CommandTestConfig.class, RestApiTestConfig.class})
public class CommandRestApiTestConfig {
    @Bean
    public CommandRestApi commandRestApi(final CommandService commandService,
                                         final UserManagementServiceI userManagementServiceI,
                                         final RoleHolder roleHolder) {
        return new CommandRestApi(commandService, userManagementServiceI, roleHolder);
    }

    @Bean
    public DockerServerService mockDockerServerService() {
        return Mockito.mock(DockerServerService.class);
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

    @Bean
    public AuthenticationManager authenticationManager() {
        return new ProviderManager(new TestingAuthenticationProvider());
    }

    // SS6: WebSecurityConfigurerAdapter removed. The original config only registered a
    // TestingAuthenticationProvider (it never customized HttpSecurity), so keep the chain permissive —
    // these MockMvc controller tests exercise the REST controllers, which enforce their own authorization
    // via the mocked RoleHolder/PermissionsService.
    @Bean
    public SecurityFilterChain securityFilterChain(final HttpSecurity http) throws Exception {
        http.csrf(csrf -> csrf.disable())
            .authorizeHttpRequests(auth -> auth.anyRequest().permitAll());
        return http.build();
    }

}
