package org.nrg.containers.jms.preferences;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.nrg.containers.config.ContainersConfig;
import org.nrg.framework.configuration.ConfigPaths;
import org.nrg.framework.services.NrgEventServiceI;
import org.nrg.framework.utilities.OrderedProperties;
import org.nrg.prefs.annotations.NrgPreference;
import org.nrg.prefs.annotations.NrgPreferenceBean;
import org.nrg.prefs.exceptions.InvalidPreferenceName;
import org.nrg.prefs.services.NrgPreferenceService;
import org.nrg.xdat.preferences.EventTriggeringAbstractPreferenceBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jms.config.JmsListenerEndpointRegistry;
import org.springframework.jms.listener.DefaultMessageListenerContainer;
import org.springframework.jms.listener.MessageListenerContainer;

import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Slf4j
@NrgPreferenceBean(toolId = "jms-queue",
        toolName = "JMS Queue Preferences",
        description = "Concurrency preferences for Container Service JMS Queues")
public class QueuePrefsBean extends EventTriggeringAbstractPreferenceBean {

    private final JmsListenerEndpointRegistry jmsListenerEndpointRegistry;

    private static final String minFinalizingPrefName = "concurrencyMinFinalizingQueue";
    private static final String maxFinalizingPrefName = "concurrencyMaxFinalizingQueue";
    private static final String minStagingPrefName = "concurrencyMinStagingQueue";
    private static final String maxStagingPrefName = "concurrencyMaxStagingQueue";


    private final HashSet<Queue> needsUpdate;
    private final HashMap<String, Integer> preferenceCache;
    private final ExecutorService executorService = Executors.newFixedThreadPool(1);

    @Getter
    private enum Queue {
        Staging(ContainersConfig.STAGING_QUEUE_CONTAINER_ID, minStagingPrefName, maxStagingPrefName),
        Finalizing(ContainersConfig.FINALIZING_QUEUE_CONTAINER_ID, minFinalizingPrefName, maxFinalizingPrefName);

        private final String listenerContainerId;
        private final String minPreferenceName;
        private final String maxPreferenceName;

        Queue(final String listenerContainerId,
              final String minPreferenceName,
              final String maxPreferenceName) {
            this.listenerContainerId = listenerContainerId;
            this.minPreferenceName = minPreferenceName;
            this.maxPreferenceName = maxPreferenceName;
        }

        public static Optional<Queue> fromPreferenceName(final String prefName) {
            for (Queue q : values()) {
                if (q.minPreferenceName.equals(prefName) || q.maxPreferenceName.equals(prefName)) {
                    return Optional.of(q);
                }
            }
            return Optional.empty();
        }
    }


    @Autowired
    public QueuePrefsBean(final NrgPreferenceService preferenceService,

                          final NrgEventServiceI eventService,
                          final ConfigPaths configPaths,
                          final OrderedProperties initPrefs,
    final JmsListenerEndpointRegistry jmsListenerEndpointRegistry) {
        super(preferenceService, eventService, configPaths, initPrefs);
        this.jmsListenerEndpointRegistry = jmsListenerEndpointRegistry;
        this.needsUpdate = new HashSet<>();
        this.preferenceCache = new HashMap<>();
        initializeCache();
    }

    private void updateConcurrencyForJMSListener(final Queue queue) {
        final String containerId = queue.getListenerContainerId();
        final MessageListenerContainer listenerContainer = jmsListenerEndpointRegistry.getListenerContainer(containerId);

        if (listenerContainer == null) {
            log.warn("No container found with ID {}. Will not configure concurrency.", containerId);
            return;
        }

        if (!(listenerContainer instanceof DefaultMessageListenerContainer)) {
            log.warn("Container with ID {} is not a DefaultMessageListenerContainer. Will not configure concurrency.", containerId);
            return;
        }

        final DefaultMessageListenerContainer defaultMessageListenerContainer = (DefaultMessageListenerContainer) listenerContainer;
        executorService.submit(() -> {
            try {
                log.debug("Stopping listener container {}", containerId);
                defaultMessageListenerContainer.stop(() -> {
                    try {
                        final String concurrencyString = preferenceCache.get(queue.minPreferenceName) + "-" + preferenceCache.get(queue.maxPreferenceName);
                        log.debug("Setting concurrency for listener container {} to {}", containerId, concurrencyString);
                        defaultMessageListenerContainer.setConcurrency(concurrencyString);

                        log.debug("Initializing listener container {}", containerId);
                        defaultMessageListenerContainer.initialize();

                        log.debug("Starting listener container {}", containerId);
                        defaultMessageListenerContainer.start();
                    } catch (Exception e) {
                        log.error("An unexpected error occurred attempting to reconfigure listener container: {}", containerId, e);
                    }
                });
            } catch (Exception e) {
                log.error("An unexpected error occurred attempting to stop listener container: {}", containerId, e);
            }
        });
    }

    private synchronized void setCachedPreferenceAndMarkQueueForUpdate(final String preferenceName, final Integer value) {
        final Optional<Queue> queue = Queue.fromPreferenceName(preferenceName);
        if (queue.isPresent()) {
            preferenceCache.put(preferenceName, value);
            markQueueForListenerRestart(queue.get());
        }
    }

    private synchronized void markQueueForListenerRestart(Queue queue) {
        needsUpdate.add(queue);
    }

    private synchronized void clearNeedsUpdate() {
        needsUpdate.clear();
    }

    private void initializeCache() {
        refreshCache(true);
    }

    private void refreshCache() {
        refreshCache(false);
    }

    private synchronized void refreshCache(boolean init) {
        for (final Queue queue : Queue.values()) {
            for (final String preference : Arrays.asList(queue.getMinPreferenceName(), queue.getMaxPreferenceName())) {
                final Integer databaseValue = getIntegerValue(preference);
                final Integer previousCachedValue = preferenceCache.put(preference, databaseValue);
                // Only mark queue for update if we are not initializing the cache and the value in the cache actually changed
                if (!init && !databaseValue.equals(previousCachedValue)) {
                    markQueueForListenerRestart(queue);
                }
            }
        }
    }

    /**
     * Public-facing method to batch-update preferences
     * <p>
     * Performs validation before saving values / updating factory concurrency
     *
     * @param prefs map of preferences
     * @throws InvalidPreferenceName for unknown preference
     */
    public synchronized void setPreferences(Map<String, Integer> prefs) throws InvalidPreferenceName {
        for (final String key : prefs.keySet()) {
            final Integer value = prefs.get(key);
            if (!getIntegerValue(key).equals(value)) {
                setCachedPreferenceAndMarkQueueForUpdate(key, value);
            }
        }

        try {
            validateSetAndUpdateConcurrency(needsUpdate);
        } finally {
            clearNeedsUpdate();
        }
    }

    /**
     * Validate user-settable preferences (ensure min <= max) and then update prefs in db and update concurrency of factory
     *
     * @throws InvalidPreferenceName for invalid values
     */
    private void validateSetAndUpdateConcurrency(final Set<Queue> toValidateAndRestart) throws InvalidPreferenceName {
        final Set<String> queuesWithInvalidValues = new HashSet<>();
        for (Queue queue : toValidateAndRestart) {
            final String minPref = queue.getMinPreferenceName();
            final String maxPref = queue.getMaxPreferenceName();

            final Integer min = preferenceCache.get(minPref);
            final Integer max = preferenceCache.get(maxPref);

            // Validate
            if (min > max) {
                // Invalid, so revert prefs "cache" to values from db and throw exception
                preferenceCache.put(minPref, getIntegerValue(minPref));
                preferenceCache.put(maxPref, getIntegerValue(maxPref));
                queuesWithInvalidValues.add(queue.toString());
                continue;
            }

            // Valid preference values, set value in database and update listener
            setIntegerValue(min, minPref);
            setIntegerValue(max, maxPref);
            updateConcurrencyForJMSListener(queue);
        }

        if (!queuesWithInvalidValues.isEmpty()) {
            throw new InvalidPreferenceName("Invalid concurrency values for " + queuesWithInvalidValues +
                    " JMS queue concurrency preferences. Be sure that min is less than or equal to max");
        }
    }

    /**
     * Get Runnable refresher that will update prefs and thus factories on shadow servers, whose beans won't be updated
     * with API changes from the tomcat server
     *
     * @param primaryNode True on tomcat server only (this update doesn't need to run there)
     * @return the runnable class
     */
    public RefreshQueuePrefs getRefresher(boolean primaryNode) {
        return new RefreshQueuePrefs(this, primaryNode);
    }

    public class RefreshQueuePrefs implements Runnable {
        private final QueuePrefsBean queuePrefsBean;
        private final boolean primaryNode;

        RefreshQueuePrefs(final QueuePrefsBean queuePrefsBean, boolean primaryNode) {
            this.queuePrefsBean = queuePrefsBean;
            this.primaryNode = primaryNode;
        }

        @Override
        public void run() {
            if (primaryNode) {
                // Skip on tomcat server because API updates will occur there, automatically changing/updating the prefs bean
                // when appropriate
                return;
            }

            try {
                refreshCache();
                needsUpdate.forEach(queuePrefsBean::updateConcurrencyForJMSListener);
            } catch (Exception e) {
                log.error("Unable to refresh JMS queue concurrency preference beans.", e);
            } finally {
                clearNeedsUpdate();
            }
        }
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
