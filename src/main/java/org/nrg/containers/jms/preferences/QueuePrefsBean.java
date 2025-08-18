package org.nrg.containers.jms.preferences;

import lombok.extern.slf4j.Slf4j;
import org.nrg.containers.config.ContainersConfig;
import org.nrg.framework.configuration.ConfigPaths;
import org.nrg.framework.services.NrgEventServiceI;
import org.nrg.framework.utilities.OrderedProperties;
import org.nrg.prefs.annotations.NrgPreference;
import org.nrg.prefs.annotations.NrgPreferenceBean;
import org.nrg.prefs.beans.AbstractPreferenceBean;
import org.nrg.prefs.exceptions.InvalidPreferenceName;
import org.nrg.prefs.services.NrgPreferenceService;
import org.nrg.xdat.preferences.EventTriggeringAbstractPreferenceBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jms.config.JmsListenerEndpointRegistry;

@Slf4j
@NrgPreferenceBean(toolId = "jms-queue",
        toolName = "JMS Queue Preferences",
        description = "Concurrency preferences for Container Service JMS Queues")
public class QueuePrefsBean extends EventTriggeringAbstractPreferenceBean {

    private static final String minFinalizingPrefName = "concurrencyMinFinalizingQueue";
    private static final String maxFinalizingPrefName = "concurrencyMaxFinalizingQueue";
    private static final String minStagingPrefName = "concurrencyMinStagingQueue";
    private static final String maxStagingPrefName = "concurrencyMaxStagingQueue";

    @Autowired
    public QueuePrefsBean(final NrgPreferenceService preferenceService,
                          final NrgEventServiceI eventService,
                          final ConfigPaths configPaths,
                          final OrderedProperties initPrefs) {
        super(preferenceService, eventService, configPaths, initPrefs);
    }

    @NrgPreference(defaultValue = ContainersConfig.QUEUE_MIN_CONCURRENCY_DFLT)
    public Integer getConcurrencyMinFinalizingQueue() {
        return getIntegerValue(minFinalizingPrefName);
    }

    public void setConcurrencyMinFinalizingQueue(Integer value) throws InvalidPreferenceName {
        setIntegerValue(value, minFinalizingPrefName);
    }

    @NrgPreference(defaultValue = ContainersConfig.QUEUE_MAX_CONCURRENCY_DFLT)
    public Integer getConcurrencyMaxFinalizingQueue() {
        return getIntegerValue(maxFinalizingPrefName);
    }

    public void setConcurrencyMaxFinalizingQueue(Integer value) throws InvalidPreferenceName {
        setIntegerValue(value, maxFinalizingPrefName);
    }


    @NrgPreference(defaultValue = ContainersConfig.QUEUE_MIN_CONCURRENCY_DFLT)
    public Integer getConcurrencyMinStagingQueue() {
        return getIntegerValue(minStagingPrefName);
    }

    public void setConcurrencyMinStagingQueue(Integer value) throws InvalidPreferenceName {
        setIntegerValue(value, minStagingPrefName);
    }

    @NrgPreference(defaultValue = ContainersConfig.QUEUE_MAX_CONCURRENCY_DFLT)
    public Integer getConcurrencyMaxStagingQueue() {
        return getIntegerValue(maxStagingPrefName);
    }

    public void setConcurrencyMaxStagingQueue(Integer value) throws InvalidPreferenceName {
        setIntegerValue(value, maxStagingPrefName);
    }
}
