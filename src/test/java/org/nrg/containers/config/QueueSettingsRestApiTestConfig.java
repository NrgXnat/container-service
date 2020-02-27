package org.nrg.containers.config;

import org.mockito.Mockito;
import org.nrg.containers.jms.preferences.QueuePrefsBean;
import org.nrg.containers.model.xnat.FakePrefsService;
import org.nrg.containers.jms.rest.QueueSettingsRestApi;
import org.nrg.containers.rest.QueueSettingsRestApiTest;
import org.nrg.framework.services.ContextService;
import org.nrg.prefs.services.NrgPreferenceService;
import org.nrg.xdat.security.services.PermissionsServiceI;
import org.nrg.xdat.security.services.RoleHolder;
import org.nrg.xdat.security.services.UserManagementServiceI;
import org.nrg.xdat.services.AliasTokenService;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.jms.config.DefaultJmsListenerContainerFactory;
import org.springframework.security.authentication.TestingAuthenticationProvider;
import org.springframework.security.config.annotation.authentication.builders.AuthenticationManagerBuilder;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configuration.WebSecurityConfigurerAdapter;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

import javax.jms.ConnectionFactory;

@Configuration
@EnableWebMvc
@EnableWebSecurity
@Import({RestApiTestConfig.class, ObjectMapperConfig.class})
public class QueueSettingsRestApiTestConfig extends WebSecurityConfigurerAdapter {
    @Bean
    public QueueSettingsRestApi queueSettingsRestApi(QueuePrefsBean queuePrefsBean,
                                                     final UserManagementServiceI mockUserManagementServiceI,
                                                     final RoleHolder roleHolder) {
        return new QueueSettingsRestApi(queuePrefsBean, mockUserManagementServiceI, roleHolder);
    }

    @Bean
    public QueuePrefsBean queuePrefsBean(final NrgPreferenceService fakePrefsService,
                                         final DefaultJmsListenerContainerFactory finalizingQueueListenerFactory,
                                         final DefaultJmsListenerContainerFactory stagingQueueListenerFactory) {
        return new QueuePrefsBean(fakePrefsService, finalizingQueueListenerFactory, stagingQueueListenerFactory);
    }

    @Bean
    public NrgPreferenceService fakePrefsService() {
        return new FakePrefsService(QueueSettingsRestApiTest.TOOL_ID,
                QueueSettingsRestApiTest.PREF_MAP);
    }

    private DefaultJmsListenerContainerFactory defaultMockFactory() {
        DefaultJmsListenerContainerFactory factory = Mockito.spy(new DefaultJmsListenerContainerFactory());
        ConnectionFactory connectionFactory = Mockito.mock(ConnectionFactory.class);
        factory.setConnectionFactory(connectionFactory);
        factory.setConcurrency(ContainersConfig.QUEUE_MIN_CONCURRENCY_DFLT + "-" + ContainersConfig.QUEUE_MAX_CONCURRENCY_DFLT);
        return factory;
    }

    @Bean
    @Qualifier("finalizingQueueListenerFactory")
    public DefaultJmsListenerContainerFactory finalizingQueueListenerFactory() {
        return defaultMockFactory();
    }

    @Bean
    @Qualifier("stagingQueueListenerFactory")
    public DefaultJmsListenerContainerFactory stagingQueueListenerFactory() {
        return defaultMockFactory();
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
