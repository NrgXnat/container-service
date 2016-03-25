package org.nrg.containers.model;

import com.google.common.collect.Lists;
import org.nrg.framework.exceptions.NrgServiceError;
import org.nrg.framework.exceptions.NrgServiceRuntimeException;
import org.nrg.prefs.annotations.NrgPreference;
import org.nrg.prefs.annotations.NrgPreferenceBean;
import org.nrg.prefs.beans.AbstractPreferenceBean;
import org.nrg.prefs.exceptions.InvalidPreferenceName;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.List;
import java.util.Map;

import static org.nrg.containers.model.ContainerHubPrefs.PREF_ID;

@NrgPreferenceBean(toolId = PREF_ID,
    toolName = "Container Hubs Prefs",
    description = "Manages the preferences for the Container Hubs",
    strict = false)
public class ContainerHubPrefs extends AbstractPreferenceBean {
    private static final Logger _log = LoggerFactory.getLogger(ContainerHubPrefs.class);
    public static final String PREF_ID = "containerHubPrefs";

//    private static final String DEFAULT_HUB =
//        String.format("{'%s':{'url':'%s','username':'%s','password':'%s','email':'%s'}}",
//            DefaultHub.key(), DefaultHub.url(), DefaultHub.username(), DefaultHub.password(), DefaultHub.email());

    public boolean hasContainerHub(final String key) {
        return getContainerHubPrefs().containsKey(key);
    }

    @NrgPreference(
        defaultValue = "{'':{'key':'','url':'https://index.docker.io/v1/','username':'','password':'','email':''}}",
        key = "key")
    public Map<String, ContainerHub> getContainerHubPrefs() {
        return getMapValue(PREF_ID);
    }

    public ContainerHub getContainerHubPref(final String key) {
        return getContainerHubPrefs().get(key);
    }

    public void setContainerHub(final ContainerHub instance) throws IOException {
        final String key = instance.key();

        if (hasContainerHub(key)) {
            deleteContainerHub(key);
        }

        try {
            set(serialize(instance), PREF_ID, key);
        } catch (InvalidPreferenceName invalidPreferenceName) {
            _log.info("Got an invalid preference name error setting Container Hub " + key);
            throw new NrgServiceRuntimeException(NrgServiceError.Unknown,
                "Could not set Container Hub " + instance);
        }
    }

    public void deleteContainerHub(final String key) {
        try {
            delete(PREF_ID, key);
        } catch (InvalidPreferenceName invalidPreferenceName) {
            _log.info("Got an invalid preference name error trying to delete Container Hub with key " + key);
        }
    }

    public List<ContainerHub> getContainerHubs() {
        return Lists.newArrayList(getContainerHubPrefs().values());
    }

    public ContainerHub getContainerHub(final String key) throws IOException, NrgServiceRuntimeException {
        if (!hasContainerHub(key)) {
            throw new NrgServiceRuntimeException(NrgServiceError.UnknownEntity,
                "There is no definition for the Container Hub with key " + key);
        }
        return getContainerHubPref(key);
    }

    public List<ContainerHub> getContainerHubsByUrl(final String url) throws IOException, NrgServiceRuntimeException {
        final List<ContainerHub> toReturn = Lists.newArrayList();
        for (final ContainerHub hub : getContainerHubs()) {
            if (hub.url().equals(url)) {
                toReturn.add(hub);
            }
        }
        return toReturn;
    }
}
