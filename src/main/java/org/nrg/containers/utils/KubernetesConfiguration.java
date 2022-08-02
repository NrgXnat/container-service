package org.nrg.containers.utils;

import io.kubernetes.client.util.FilePersister;
import io.kubernetes.client.util.KubeConfig;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang.SystemUtils;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Util methods to find kubeconfig file in standard locations
 * This should not exist in this repo forever. The plan is for this to exist in the kubernetes client library.
 */
@Slf4j
public class KubernetesConfiguration {
    public static final String KUBEDIR = ".kube";
    public static final String KUBECONFIG = "config";
    public static final Path KUBECONFIG_RELATIVEPATH = Paths.get(KUBEDIR, KUBECONFIG);
    public static final String ENV_HOME = "HOME";
    public static final String ENV_HOMEDRIVE = "HOMEDRIVE";
    public static final String ENV_HOMEPATH = "HOMEPATH";
    public static final String ENV_KUBECONFIG = "KUBECONFIG";
    public static final String ENV_USERPROFILE = "USERPROFILE";

    private static Path findConfigFromEnv() {
        final KubeConfigEnvParser kubeConfigEnvParser = new KubeConfigEnvParser();

        final String kubeConfigPath =
                kubeConfigEnvParser.parseKubeConfigPath(System.getenv(ENV_KUBECONFIG));
        if (kubeConfigPath == null) {
            return null;
        }
        final Path kubeConfig = Paths.get(kubeConfigPath);
        if (Files.exists(kubeConfig)) {
            return kubeConfig;
        } else {
            log.debug("Could not find file specified in $KUBECONFIG");
            return null;
        }
    }

    private static class KubeConfigEnvParser {
        private String parseKubeConfigPath(String kubeConfigEnv) {
            if (kubeConfigEnv == null) {
                return null;
            }

            final String[] filePaths = kubeConfigEnv.split(File.pathSeparator);
            final String kubeConfigPath = filePaths[0];
            if (filePaths.length > 1) {
                log.warn(
                        "Found multiple kubeconfigs files, $KUBECONFIG: " + kubeConfigEnv + " using first: {}",
                        kubeConfigPath);
            }

            return kubeConfigPath;
        }
    }

    private static Path findHomeDir() {
        final String envHome = System.getenv(ENV_HOME);
        if (envHome != null && envHome.length() > 0) {
            final Path config = Paths.get(envHome);
            if (Files.exists(config)) {
                return config;
            }
        }
        if (SystemUtils.IS_OS_WINDOWS) {
            String homeDrive = System.getenv(ENV_HOMEDRIVE);
            String homePath = System.getenv(ENV_HOMEPATH);
            if (homeDrive != null
                    && homeDrive.length() > 0
                    && homePath != null
                    && homePath.length() > 0) {
                Path homeDir = Paths.get(homeDrive, homePath);
                if (Files.exists(homeDir)) {
                    return homeDir;
                }
            }
            String userProfile = System.getenv(ENV_USERPROFILE);
            if (userProfile != null && userProfile.length() > 0) {
                Path profileDir = Paths.get(userProfile);
                if (Files.exists(profileDir)) {
                    return profileDir;
                }
            }
        }
        return null;
    }

    private static Path findConfigInHomeDir() {
        final Path homeDir = findHomeDir();
        if (homeDir == null) {
            log.debug("Could not find kubeconfig file because could not determine home directory");
            return null;
        }
        final Path config = homeDir.resolve(KUBECONFIG_RELATIVEPATH);
        if (Files.exists(config)) {
            return config;
        }
        log.debug("Could not find kubeconfig file at {}", config);
        return null;
    }

    /**
     * Finds kube config file in standard locations
     *
     * <ul>
     *   <li>If $KUBECONFIG is defined, use that config file.
     *   <li>If $HOME/.kube/config can be found, use that.
     * </ul>
     *
     * If a file cannot be found in those locations, returns null.
     *
     * @return A {@link File} pointing to a kube config, or null
     */
    public static Path find() {
        final Path fromEnv = findConfigFromEnv();
        return fromEnv != null ? fromEnv : findConfigInHomeDir();
    }

    /**
     * Loads kube config file from standard locations
     *
     * <ul>
     *   <li>If $KUBECONFIG is defined, use that config file.
     *   <li>If $HOME/.kube/config can be found, use that.
     * </ul>
     *
     * If a file cannot be found in those locations, returns null.
     *
     * @return A <tt>KubeConfig</tt> if it can be found, or null
     * @throws IOException if the configuration file or a file specified in a configuration file
     *     cannot be read.
     */
    public static KubeConfig loadStandard() throws IOException {
        return loadKubeConfig(find(), false);
    }

    public static KubeConfig loadStandard(boolean persistConfig) throws IOException {
        return loadKubeConfig(find(), persistConfig);
    }

    public static KubeConfig loadKubeConfig(String filename, boolean persistConfig) throws IOException {
        return loadKubeConfig(Paths.get(filename), persistConfig);
    }

    public static KubeConfig loadKubeConfig(Path kubeConfig, boolean persistConfig) throws IOException {
        if (kubeConfig != null) {
            final File kcFile = kubeConfig.toFile();
            try (BufferedReader kubeConfigReader =
                         new BufferedReader(
                                 new InputStreamReader(
                                         Files.newInputStream(kubeConfig), StandardCharsets.UTF_8.name()))) {
                KubeConfig kc = KubeConfig.loadKubeConfig(kubeConfigReader);
                if (persistConfig) {
                    kc.setPersistConfig(new FilePersister(kcFile));
                }
                kc.setFile(kcFile);
                return kc;
            }
        }
        return null;
    }

}
