package org.nrg.containers.secrets;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.commons.lang3.StringUtils;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mockito;
import org.nrg.containers.api.ContainerControlApi;
import org.nrg.containers.config.ObjectMapperConfig;
import org.nrg.containers.exceptions.CommandResolutionException;
import org.nrg.containers.model.command.auto.Command;
import org.nrg.containers.model.command.auto.ResolvedCommand;
import org.nrg.containers.model.server.docker.Backend;
import org.nrg.containers.model.server.docker.DockerServerBase;
import org.nrg.containers.model.server.docker.JobNamespaceMode;
import org.nrg.containers.model.server.docker.SecretVolumeType;
import org.nrg.containers.services.CommandResolutionService;
import org.nrg.containers.services.CommandService;
import org.nrg.containers.services.ContainerSecretService;
import org.nrg.containers.services.DockerServerService;
import org.nrg.containers.services.DockerService;
import org.nrg.containers.services.impl.CommandResolutionServiceImpl;
import org.nrg.containers.services.impl.ContainerSecretServiceImpl;
import org.nrg.xdat.preferences.SiteConfigPreferences;
import org.nrg.xdat.services.cache.UserDataCache;
import org.nrg.xft.security.UserI;
import org.nrg.xnat.services.archive.CatalogService;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.fail;
import static org.mockito.Mockito.when;

/**
 * Secret names are rendered, sanitised, validated and written back into the source during command resolution
 * (design doc sections 2.3 to 2.5, 2.8), not at job creation.
 */
public class FileSecretResolutionTest {
    private static final ObjectMapper MAPPER = new ObjectMapperConfig().objectMapper();
    private static final String MOUNT = "/run/secrets/redcap";

    private final DockerServerService dockerServerService = Mockito.mock(DockerServerService.class);
    private final SystemPropertySecretSource.ValueObtainer valueObtainer = Mockito.mock(SystemPropertySecretSource.ValueObtainer.class);
    private final UserI user = Mockito.mock(UserI.class);
    private final ContainerControlApi containerControlApi = Mockito.mock(ContainerControlApi.class);
    private CommandResolutionService service;

    @Before
    public void setUp() {
        when(user.getLogin()).thenReturn("tester");
        when(valueObtainer.handledType()).thenReturn(SystemPropertySecretSource.class);
        final ContainerSecretService secretService = new ContainerSecretServiceImpl(Collections.singletonList(valueObtainer));
        service = new CommandResolutionServiceImpl(
                Mockito.mock(CommandService.class), dockerServerService, Mockito.mock(SiteConfigPreferences.class), MAPPER,
                Mockito.mock(DockerService.class), Mockito.mock(CatalogService.class), Mockito.mock(UserDataCache.class), secretService,
                containerControlApi);
    }

    static DockerServerBase.DockerServer.Builder kubernetesServer() {
        return DockerServerBase.DockerServer.builder().name("k8s").host("").backend(Backend.KUBERNETES);
    }

    static Secret fileSecret(final String identifier, final String name, final String mountPath) {
        return new Secret(new K8sSecretSource(identifier, name), new FileSecretDestination(mountPath));
    }

    ResolvedCommand resolve(final List<Secret> secrets, final String project, final Map<String, String> inputValues,
                            final DockerServerBase.DockerServer server) throws Exception {
        when(dockerServerService.getServer()).thenReturn(server);
        final Command.CommandWrapper wrapper = Command.CommandWrapper.builder().name("wrapper").build();
        final Command.CommandInput sessionInput = Command.CommandInput.builder().name("session").type("string").build();
        final Command command = Command.builder()
                .name("cmd").image("busybox:latest").commandLine("cat " + MOUNT + "/client_id")
                .addInput(sessionInput)
                .secrets(secrets)
                .addCommandWrapper(wrapper)
                .build();
        // ConfiguredCommand.initialize does not copy inputs (CommandConfiguration.apply adds them one by one)
        final Command.ConfiguredCommand configured = Command.ConfiguredCommand.initialize(command)
                .wrapper(wrapper)
                .addInput(sessionInput)
                .build();
        return service.resolve(configured, inputValues, project, user, null);
    }

    private FileResolvedSecret onlyFileSecret(final ResolvedCommand resolved) {
        assertThat(resolved.secrets(), hasSize(1));
        assertThat(resolved.secrets().get(0), instanceOf(FileResolvedSecret.class));
        return (FileResolvedSecret) resolved.secrets().get(0);
    }

    private void assertResolutionFails(final List<Secret> secrets, final String project, final Map<String, String> inputValues,
                                       final DockerServerBase.DockerServer server, final String expectedFragment) throws Exception {
        try {
            resolve(secrets, project, inputValues, server);
            fail("Expected CommandResolutionException containing: " + expectedFragment);
        } catch (CommandResolutionException e) {
            assertThat(e.getMessage(), containsString(expectedFragment));
        }
    }

    @Test
    public void builtInDefaultIsTheIdentifierAloneAndWritesBackTheName() throws Exception {
        final FileResolvedSecret secret = onlyFileSecret(resolve(
                Collections.singletonList(fileSecret("redcap", null, MOUNT)), "Proj_01", Collections.emptyMap(), kubernetesServer().build()));

        assertThat(secret.secretName(), is("redcap"));
        assertThat(secret.required(), is(true));
        assertThat("written back into the serialisable source", ((K8sSecretSource) secret.source()).secretName(), is("redcap"));
        assertThat("the declared template is not overwritten", ((K8sSecretSource) secret.source()).nameTemplate(), is(nullValue()));
    }

    /** Zero configuration must work for a launch that has no project, e.g. a site-level command. */
    @Test
    public void builtInDefaultDoesNotNeedAProject() throws Exception {
        assertThat(onlyFileSecret(resolve(Collections.singletonList(fileSecret("redcap", null, MOUNT)), null, Collections.emptyMap(),
                kubernetesServer().build())).secretName(), is("redcap"));
    }

    @Test
    public void commandNameBeatsBackendTemplateWhichBeatsBuiltInDefault() throws Exception {
        final DockerServerBase.DockerServer server = kubernetesServer().secretNameTemplate("site-#PROJECT#-{identifier}").build();
        assertThat(onlyFileSecret(resolve(Collections.singletonList(fileSecret("redcap", null, MOUNT)), "PROJ01", Collections.emptyMap(), server)).secretName(),
                is("site-proj01-redcap"));
        assertThat(onlyFileSecret(resolve(Collections.singletonList(fileSecret("redcap", "#PROJECT#-{identifier}", MOUNT)), "PROJ01", Collections.emptyMap(), server)).secretName(),
                is("proj01-redcap"));
    }

    @Test
    public void inputReplacementKeysAreAvailableAsPlaceholders() throws Exception {
        final FileResolvedSecret secret = onlyFileSecret(resolve(
                Collections.singletonList(fileSecret("redcap", "{identifier}-#session#", MOUNT)), "PROJ01",
                Collections.singletonMap("session", "SESS_1"), kubernetesServer().build()));
        assertThat(secret.secretName(), is("redcap-sess-1"));
    }

    @Test
    public void unresolvedPlaceholderAndMissingProjectFail() throws Exception {
        assertResolutionFails(Collections.singletonList(fileSecret("redcap", "{identifier}-#nope#", MOUNT)), "PROJ01", Collections.emptyMap(),
                kubernetesServer().build(), "Placeholder #nope# has no value");
        assertResolutionFails(Collections.singletonList(fileSecret("redcap", null, MOUNT)), null, Collections.emptyMap(),
                kubernetesServer().secretNameTemplate("xnat-#PROJECT#-{identifier}").build(), "#PROJECT# has no value");
        assertResolutionFails(Collections.singletonList(fileSecret("redcap", null, MOUNT)), "PROJ01", Collections.emptyMap(),
                kubernetesServer().secretNameTemplate("site-#PROJECT#").build(), "{identifier}");
        assertResolutionFails(Collections.singletonList(fileSecret("redcap", "fixed-name", MOUNT)), "PROJ01", Collections.emptyMap(),
                kubernetesServer().build(), "{identifier}");
        assertResolutionFails(Collections.singletonList(fileSecret("redcap", null, MOUNT)), StringUtils.repeat('p', 250), Collections.emptyMap(),
                kubernetesServer().secretNameTemplate("xnat-#PROJECT#-{identifier}").build(), "253");
    }

    @Test
    public void identifierAndMountPathRulesAreEnforced() throws Exception {
        assertResolutionFails(Arrays.asList(fileSecret("RedCap", null, "/run/secrets/a"), fileSecret("redcap", null, "/run/secrets/b")),
                "PROJ01", Collections.emptyMap(), kubernetesServer().build(), "not unique");
        assertResolutionFails(Arrays.asList(fileSecret("a", null, "/run/secrets/a"), fileSecret("b", null, "/run/secrets/a/b")),
                "PROJ01", Collections.emptyMap(), kubernetesServer().build(), "nest");
        assertResolutionFails(Collections.singletonList(fileSecret("a", null, "/run/secrets")),
                "PROJ01", Collections.emptyMap(), kubernetesServer().build(), "subdirectory");
        assertResolutionFails(Collections.singletonList(fileSecret("a", null, "/run/secrets/../a")),
                "PROJ01", Collections.emptyMap(), kubernetesServer().build(), "..");
    }

    /** File secrets are validated before rendering, so a bad identifier gets the validator's message, not the renderer's. */
    @Test
    public void blankIdentifierIsReportedByTheValidator() throws Exception {
        assertResolutionFails(Collections.singletonList(fileSecret("", null, MOUNT)), "PROJ01", Collections.emptyMap(),
                kubernetesServer().build(), "Secret source identifier is required for file secrets");
    }

    /** The mount path is not a secret; the upstream "inputs must not reference a secret destination" check is for env secrets only. */
    @Test
    public void inputsMayMentionTheMountPathButNotAnEnvSecretName() throws Exception {
        final ResolvedCommand ok = resolve(Collections.singletonList(fileSecret("redcap", null, MOUNT)), "PROJ01",
                Collections.singletonMap("session", MOUNT + "/client_id"), kubernetesServer().build());
        assertThat(ok.secrets(), hasSize(1));

        when(valueObtainer.obtainValue(Mockito.any())).thenReturn(Optional.of("v"));
        final Secret env = new Secret(new SystemPropertySecretSource("sys.prop"), new EnvironmentVariableSecretDestination("SECRET_ENV"));
        assertResolutionFails(Collections.singletonList(env), "PROJ01", Collections.singletonMap("session", "uses SECRET_ENV"),
                kubernetesServer().build(), "refers to secret destination");
    }

    @Test
    public void perProjectModeRendersTheNamespaceEvenWithoutSecrets() throws Exception {
        final DockerServerBase.DockerServer server = kubernetesServer()
                .jobNamespaceMode(JobNamespaceMode.PER_PROJECT).jobNamespaceTemplate("xnat-#PROJECT#").build();
        assertThat(resolve(Collections.emptyList(), "My_Proj", Collections.emptyMap(), server).namespace(), is("xnat-my-proj"));
        assertThat(resolve(Collections.singletonList(fileSecret("redcap", null, MOUNT)), "PROJ01", Collections.emptyMap(), server).namespace(), is("xnat-proj01"));
    }

    @Test
    public void singleModeLeavesTheNamespaceToTheContext() throws Exception {
        assertThat(resolve(Collections.emptyList(), "PROJ01", Collections.emptyMap(), kubernetesServer().build()).namespace(), is(nullValue()));
    }

    @Test
    public void perProjectModeFailsWithoutAProjectOrWithAnOverlongName() throws Exception {
        final DockerServerBase.DockerServer server = kubernetesServer()
                .jobNamespaceMode(JobNamespaceMode.PER_PROJECT).jobNamespaceTemplate("xnat-#PROJECT#").build();
        assertResolutionFails(Collections.emptyList(), null, Collections.emptyMap(), server, "#PROJECT# has no value");
        assertResolutionFails(Collections.emptyList(), StringUtils.repeat('p', 60), Collections.emptyMap(), server, "63");
    }

    /**
     * Only Kubernetes can mount file secrets, so other backends refuse them at resolution with the launch-time wording,
     * before a site-level launch could report a misleading "#PROJECT# has no value".
     */
    @Test
    public void nonKubernetesBackendsRefuseFileSecretsAtResolution() throws Exception {
        final DockerServerBase.DockerServer docker = DockerServerBase.DockerServer.builder().name("d").host("unix:///var/run/docker.sock").backend(Backend.DOCKER).build();
        assertResolutionFails(Collections.singletonList(fileSecret("redcap", null, MOUNT)), null, Collections.emptyMap(), docker,
                "File secrets are not supported on the " + Backend.DOCKER + " backend");
        assertResolutionFails(Collections.singletonList(fileSecret("redcap", null, MOUNT)), "PROJ01", Collections.emptyMap(),
                docker.toBuilder().backend(Backend.SWARM).build(), "File secrets are not supported on the " + Backend.SWARM + " backend");
    }

    /** A per-project template so the SecretProviderClass checks below can tell projects apart. */
    private static DockerServerBase.DockerServer csiServer() {
        return kubernetesServer().secretVolumeType(SecretVolumeType.CSI).secretNameTemplate("xnat-#PROJECT#-{identifier}").build();
    }

    @Test
    public void csiModeKeepsAnExistingSecretProviderClass() throws Exception {
        when(containerControlApi.secretProviderClassExists(null, "xnat-proj01-redcap")).thenReturn(SecretProviderClassCheck.exists());
        assertThat(onlyFileSecret(resolve(Collections.singletonList(fileSecret("redcap", null, MOUNT)), "PROJ01", Collections.emptyMap(), csiServer())).secretName(),
                is("xnat-proj01-redcap"));
    }

    @Test
    public void csiModeFailsWhenARequiredSecretProviderClassIsMissing() throws Exception {
        when(containerControlApi.secretProviderClassExists(null, "xnat-proj01-redcap"))
                .thenReturn(SecretProviderClassCheck.missing("SecretProviderClass \"xnat-proj01-redcap\" does not exist in namespace \"ctx\""));
        assertResolutionFails(Collections.singletonList(fileSecret("redcap", null, MOUNT)), "PROJ01", Collections.emptyMap(), csiServer(),
                "SecretProviderClass \"xnat-proj01-redcap\" does not exist");
    }

    @Test
    public void csiModeDropsAnOptionalMissingSecretProviderClass() throws Exception {
        when(containerControlApi.secretProviderClassExists(null, "xnat-proj01-redcap"))
                .thenReturn(SecretProviderClassCheck.missing("missing"));
        when(containerControlApi.secretProviderClassExists(null, "xnat-proj01-openspecimen")).thenReturn(SecretProviderClassCheck.exists());
        final Secret optional = new Secret(new K8sSecretSource("redcap", null, false), new FileSecretDestination(MOUNT));
        final ResolvedCommand resolved = resolve(Arrays.asList(optional, fileSecret("openspecimen", null, "/run/secrets/openspecimen")),
                "PROJ01", Collections.emptyMap(), csiServer());
        assertThat(resolved.secrets(), hasSize(1));
        assertThat(((FileResolvedSecret) resolved.secrets().get(0)).identifier(), is("openspecimen"));
    }

    @Test
    public void csiModeFailsWhenTheCheckIsUndeterminedEvenForOptionalSecrets() throws Exception {
        when(containerControlApi.secretProviderClassExists(null, "xnat-proj01-redcap"))
                .thenReturn(SecretProviderClassCheck.undetermined("The Container Service identity lacks get permission on secretproviderclasses in namespace \"ctx\""));
        final Secret optional = new Secret(new K8sSecretSource("redcap", null, false), new FileSecretDestination(MOUNT));
        assertResolutionFails(Collections.singletonList(optional), "PROJ01", Collections.emptyMap(), csiServer(), "lacks get permission");
    }

    @Test
    public void csiCheckUsesThePerProjectNamespace() throws Exception {
        final DockerServerBase.DockerServer server = kubernetesServer().secretVolumeType(SecretVolumeType.CSI)
                .jobNamespaceMode(JobNamespaceMode.PER_PROJECT).jobNamespaceTemplate("xnat-#PROJECT#").build();
        when(containerControlApi.secretProviderClassExists("xnat-proj01", "redcap")).thenReturn(SecretProviderClassCheck.exists());
        assertThat(resolve(Collections.singletonList(fileSecret("redcap", null, MOUNT)), "PROJ01", Collections.emptyMap(), server).secrets(), hasSize(1));
    }

    /** In secret mode the object cannot be checked without reading the value, so nothing is asked. */
    @Test
    public void secretModeSkipsTheExistenceCheck() throws Exception {
        resolve(Collections.singletonList(fileSecret("redcap", null, MOUNT)), "PROJ01", Collections.emptyMap(), kubernetesServer().build());
        Mockito.verifyNoInteractions(containerControlApi);
    }
}
