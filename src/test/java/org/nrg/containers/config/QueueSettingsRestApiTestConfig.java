package org.nrg.containers.config;

import org.mockito.Mockito;
import org.nrg.containers.jms.preferences.QueuePrefsBean;
import org.nrg.containers.jms.rest.QueueSettingsRestApi;
import org.nrg.containers.model.xnat.FakePrefsService;
import org.nrg.containers.rest.QueueSettingsRestApiTest;
import org.nrg.framework.services.ContextService;
import org.nrg.framework.services.SerializerService;
import org.nrg.prefs.services.NrgPreferenceService;
import org.nrg.xdat.security.services.PermissionsServiceI;
import org.nrg.xdat.security.services.RoleHolder;
import org.nrg.xdat.security.services.UserManagementServiceI;
import org.nrg.xdat.services.AliasTokenService;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.jms.config.JmsListenerEndpointRegistry;
import org.springframework.security.authentication.TestingAuthenticationProvider;
import org.springframework.security.config.annotation.authentication.builders.AuthenticationManagerBuilder;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configuration.WebSecurityConfigurerAdapter;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

@Configuration
@EnableWebMvc
@EnableWebSecurity
@Import({RestApiTestConfig.class, ObjectMapperConfig.class})
public class QueueSettingsRestApiTestConfig extends WebSecurityConfigurerAdapter {

    @Bean
    public JmsListenerEndpointRegistry jmsListenerEndpointRegistry() {
        return Mockito.mock(JmsListenerEndpointRegistry.class);
    }


    @Bean
    public QueueSettingsRestApi queueSettingsRestApi(QueuePrefsBean queuePrefsBean,
                                                     final UserManagementServiceI mockUserManagementServiceI,
                                                     final RoleHolder roleHolder) {
        return new QueueSettingsRestApi(queuePrefsBean, mockUserManagementServiceI, roleHolder);
    }

    @Bean
    public QueuePrefsBean queuePrefsBean(final NrgPreferenceService fakePrefsService,
                                         final JmsListenerEndpointRegistry registry) {
        return new QueuePrefsBean(fakePrefsService, registry);
    }

    @Bean
    public SerializerService serializerService() {
        return Mockito.mock(SerializerService.class);
    }

    @Bean
    public NrgPreferenceService fakePrefsService() {
        return new FakePrefsService(QueueSettingsRestApiTest.QUEUE_PREFS_BEAN_TOOL_ID,
                QueueSettingsRestApiTest.DEFAULT_CONCURRENCY_PREFERENCES);
    }

    @Bean
    public AliasTokenService aliasTokenService() {
        return Mockito.mock(AliasTokenService.class);
    }

    @Bean
    public PermissionsServiceI permissionsService() {
        return Mockito.mock(PermissionsServiceI.class);
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
