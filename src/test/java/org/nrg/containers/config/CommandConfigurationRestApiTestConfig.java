package org.nrg.containers.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.mockito.Mockito;
import org.nrg.config.services.ConfigService;
import org.nrg.containers.daos.CommandEntityRepository;
import org.nrg.containers.daos.OrchestrationEntityDao;
import org.nrg.containers.daos.OrchestrationProjectEntityDao;
import org.nrg.containers.rest.CommandConfigurationRestApi;
import org.nrg.containers.services.*;
import org.nrg.containers.services.impl.*;
import org.nrg.config.services.ConfigService;
import org.nrg.framework.services.ContextService;
import org.nrg.xdat.security.services.PermissionsServiceI;
import org.nrg.xdat.security.services.RoleHolder;
import org.nrg.xdat.security.services.UserManagementServiceI;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.security.authentication.TestingAuthenticationProvider;
import org.springframework.security.config.annotation.authentication.builders.AuthenticationManagerBuilder;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configuration.WebSecurityConfigurerAdapter;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

@Configuration
@EnableWebMvc
@EnableWebSecurity
@Import({RestApiTestConfig.class})
public class CommandConfigurationRestApiTestConfig extends WebSecurityConfigurerAdapter {
    @Bean
    public CommandConfigurationRestApi commandConfigurationRestApi(final CommandService commandService,
                                                                   final UserManagementServiceI userManagementServiceI,
                                                                   final RoleHolder roleHolder,
                                                                   final PermissionsServiceI permissionService,
                                                                   final NamedParameterJdbcTemplate template
                                                                   ) {
        return new CommandConfigurationRestApi(commandService, userManagementServiceI, roleHolder, permissionService, template);
    }

    @Bean
    public CommandService commandService(final CommandEntityService commandEntityService,
                                         final ContainerConfigService containerConfigService) {
        return new CommandServiceImpl(commandEntityService, containerConfigService);
    }

    @Bean
    public CommandEntityService mockCommandEntityService() {
        return Mockito.mock(CommandEntityService.class);
    }

    @Bean
    public ContainerConfigService containerConfigService(final ConfigService configService,
                                                         final ObjectMapper objectMapper) {
        return new ContainerConfigServiceImpl(configService, objectMapper, Mockito.mock(OrchestrationProjectEntityService.class), Mockito.mock(OrchestrationEntityService.class));
    }

    @Bean
    public ConfigService configService() {
        return Mockito.mock(ConfigService.class);
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

    @Override
    protected void configure(AuthenticationManagerBuilder auth) throws Exception {
        auth.authenticationProvider(new TestingAuthenticationProvider());
    }

}
