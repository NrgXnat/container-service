package org.nrg.containers.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.mockito.Mockito;
import org.nrg.framework.node.NodeLeader;
import org.nrg.framework.node.NodeLeaderListener;
import org.nrg.framework.node.NodeLockService;
import org.nrg.mail.services.MailService;
import org.nrg.xdat.security.services.RoleHolder;
import org.nrg.xdat.security.services.RoleServiceI;
import org.nrg.xdat.security.services.UserManagementServiceI;
import org.nrg.xnat.services.XnatAppInfo;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.converter.StringHttpMessageConverter;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.scheduling.concurrent.ThreadPoolExecutorFactoryBean;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurerAdapter;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.mockito.Mockito.when;

@Configuration
@Import({ObjectMapperConfig.class})
public class RestApiTestConfig extends WebMvcConfigurerAdapter {
    @Bean
    @Qualifier("mockXnatAppInfo")
    public XnatAppInfo mockAppInfo() {
        XnatAppInfo mockXnatAppInfo = Mockito.mock(XnatAppInfo.class);
        when(mockXnatAppInfo.isPrimaryNode()).thenReturn(true);
        return mockXnatAppInfo;
    }

    /**
     * A node lock service on which this node leads every lock at once, as a single node does.
     */
    @Bean
    public NodeLockService mockNodeLockService() {
        final NodeLockService nodeLockService = Mockito.mock(NodeLockService.class);
        when(nodeLockService.isEnabled()).thenReturn(true);
        when(nodeLockService.registerLeader(Mockito.anyString(), Mockito.any(NodeLeaderListener.class))).thenAnswer(invocation -> {
            final String name = invocation.getArgument(0);
            final NodeLeader leader = Mockito.mock(NodeLeader.class);
            when(leader.getName()).thenReturn(name);
            when(leader.isLeader()).thenReturn(true);
            when(leader.stepDown(Mockito.any())).thenReturn(false);
            return leader;
        });
        return nodeLockService;
    }

    @Bean
    public ExecutorService executorService() {
        return Executors.newCachedThreadPool();
    }

    @Bean
    public ThreadPoolExecutorFactoryBean syncThreadPoolExecutorFactoryBean(ExecutorService executorService) {
        ThreadPoolExecutorFactoryBean tBean = Mockito.mock(ThreadPoolExecutorFactoryBean.class);
        when(tBean.getObject()).thenReturn(executorService);
        return tBean;
    }

    @Bean
    public MailService mockMailService() {
        return Mockito.mock(MailService.class);
    }

    @Bean
    @Qualifier("mockRoleService")
    public RoleServiceI mockRoleService() {
        return Mockito.mock(RoleServiceI.class);
    }

    @Bean
    public RoleHolder mockRoleHolder(@Qualifier("mockRoleService") final RoleServiceI roleServiceI,
                                     @Qualifier("mockNamedParameterJdbcTemplate") final NamedParameterJdbcTemplate namedParameterJdbcTemplate) {
        return new RoleHolder(roleServiceI, namedParameterJdbcTemplate);
    }

    @Bean
    public NamedParameterJdbcTemplate mockNamedParameterJdbcTemplate() {
        return Mockito.mock(NamedParameterJdbcTemplate.class);
    }

    @Bean
    public UserManagementServiceI mockUserManagementServiceI() {
        return Mockito.mock(UserManagementServiceI.class);
    }

    @Override
    public void configureMessageConverters(List<HttpMessageConverter<?>> converters) {
        converters.add(new StringHttpMessageConverter());
        converters.add(new MappingJackson2HttpMessageConverter(objectMapper));
    }

    @Autowired
    private ObjectMapper objectMapper;
}
