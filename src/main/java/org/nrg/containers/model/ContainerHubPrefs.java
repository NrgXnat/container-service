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

    public boolean hasContainerHub(final String url) {
        for (final ContainerHub instance : getContainerHubPrefs()) {
            if (instance.url().equals(url)) {
                return true;
            }
        }
        return false;
    }

    @NrgPreference(defaultValue = "[{'url':'https://index.docker.io/v1/', 'name':'Docker Hub'}]", key = "url")
    public List<ContainerHub> getContainerHubPrefs() {
        return getListValue(PREF_ID);
    }

    public ContainerHub getContainerHubPref(final String url) throws IOException {
        final String value = getValue(PREF_ID, url);
        return deserialize(value, ContainerHub.class);
    }

    public void setContainerHub(final ContainerHub instance) throws IOException {
        final String instanceId = getPrefId(PREF_ID, instance);

        try {
            set(serialize(instance), PREF_ID, instanceId);
        } catch (InvalidPreferenceName invalidPreferenceName) {
            _log.info("Got an invalid preference name error setting Container Hub " + instanceId);
            throw new NrgServiceRuntimeException(NrgServiceError.Unknown,
                "Could not set Container Hub " + instance);
        }
    }

    public void deleteContainerHub(final ContainerHub instance) {
        final String instanceId = getPrefId(PREF_ID, instance);
        try {
            delete(PREF_ID, instanceId);
        } catch (InvalidPreferenceName invalidPreferenceName) {
            _log.info("Got an invalid preference name error trying to delete Container Hub with id " + instanceId);
        }
    }

    public List<ContainerHub> getContainerHubs() {
        return getContainerHubPrefs();
    }


    private String getPrefId(final String prefId, final ContainerHub instance) {
        final String url = instance.url();
        return getNamespacedPropertyId(prefId, url);
    }
}
