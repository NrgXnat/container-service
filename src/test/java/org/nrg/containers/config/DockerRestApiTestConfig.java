package org.nrg.containers.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.mockito.Mockito;
import org.nrg.containers.rest.DockerRestApi;
import org.nrg.containers.services.DockerService;
import org.nrg.framework.services.ContextService;
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
    public DockerService dockerService() {
        return Mockito.mock(DockerService.class);
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
