package org.nrg.containers.jms.preferences;

import lombok.extern.slf4j.Slf4j;
import org.nrg.containers.config.ContainersConfig;
import org.nrg.prefs.annotations.NrgPreference;
import org.nrg.prefs.annotations.NrgPreferenceBean;
import org.nrg.prefs.beans.AbstractPreferenceBean;
import org.nrg.prefs.exceptions.InvalidPreferenceName;
import org.nrg.prefs.exceptions.UnknownToolId;
import org.nrg.prefs.services.NrgPreferenceService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jms.config.DefaultJmsListenerContainerFactory;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;

@Slf4j
@NrgPreferenceBean(toolId = "jms-queue",
        toolName = "JMS Queue Preferences",
        description = "Concurrency preferences for Container Service JMS Queues")
public class QueuePrefsBean extends AbstractPreferenceBean {

    private final DefaultJmsListenerContainerFactory finalizingQueueListenerFactory;
    private final DefaultJmsListenerContainerFactory stagingQueueListenerFactory;

    private final int minConcurrencyDflt = Integer.parseInt(ContainersConfig.QUEUE_MIN_CONCURRENCY_DFLT);
    private final int maxConcurrencyDflt = Integer.parseInt(ContainersConfig.QUEUE_MAX_CONCURRENCY_DFLT);

    private static final String minFinalizingPrefName = makePrefNameFromQueueAndBound(Queue.Finalizing, Bound.Min);
    private static final String maxFinalizingPrefName = makePrefNameFromQueueAndBound(Queue.Finalizing, Bound.Max);
    private static final String minStagingPrefName = makePrefNameFromQueueAndBound(Queue.Staging, Bound.Min);
    private static final String maxStagingPrefName = makePrefNameFromQueueAndBound(Queue.Staging, Bound.Max);

    private final HashSet<Queue> needsUpdate;
    private HashMap<QueueBound, Integer> desiredPrefs;

    private enum Bound {
        Min,
        Max
    }
    private enum Queue {
        Staging,
        Finalizing
    }

    @Autowired
    public QueuePrefsBean(final NrgPreferenceService preferenceService,
                          final DefaultJmsListenerContainerFactory finalizingQueueListenerFactory,
                          final DefaultJmsListenerContainerFactory stagingQueueListenerFactory) {
        super(preferenceService);
        this.finalizingQueueListenerFactory = finalizingQueueListenerFactory;
        this.stagingQueueListenerFactory = stagingQueueListenerFactory;
        this.needsUpdate = new HashSet<>();

        // Populate "cache"
        this.desiredPrefs = new HashMap<>();
        refreshCache(true);
    }

    /**
     * This QueueBound class is meant to be a convenient way to call getters & setters and determine default values.
     */
    private class QueueBound {
        Queue queue;
        Bound bound;
        String prefName;
        public Integer defaultValue;

        QueueBound(Queue queue, Bound bound) {
            this.queue = queue;
            this.bound = bound;
            this.prefName = makePrefNameFromQueueAndBound(queue, bound);
            switch (this.bound) {
                case Min:
                    this.defaultValue = minConcurrencyDflt;
                    break;
                case Max:
                    this.defaultValue = maxConcurrencyDflt;
                    break;
            }
        }

        Integer invokeGetter() {
            // No reason to deal with reflection since our methods just run this
            //Method getter = QueuePrefsBean.class.getDeclaredMethod("get" + prefName.substring(0,1).toUpperCase() +
            //        prefName.substring(1));
            //return (Integer) getter.invoke(QueuePrefsBean.this);
            return getIntegerValue(prefName);
        }

        void invokeSetter(Integer value) throws InvalidPreferenceName {
            // No reason to deal with reflection since our methods just run this
            //Method setter = QueuePrefsBean.class.getDeclaredMethod("set" + prefName.substring(0,1).toUpperCase() +
            //        prefName.substring(1), Integer.class);
            //setter.invoke(QueuePrefsBean.this, value);
            setIntegerValue(value, prefName);
        }

        @Override
        public boolean equals(final Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            final QueueBound that = (QueueBound) o;
            return this.queue == that.queue && this.bound == that.bound;
        }

        @Override
        public int hashCode() {
            return Objects.hash(queue, bound);
        }
    }

    private QueueBound getQueueBoundFromPrefName(String prefName) throws InvalidPreferenceName {
        for (QueueBound qb : desiredPrefs.keySet()) {
            if (qb.prefName.equals(prefName)) {
                return qb;
            }
        }
        // If we get here, we couldn't match the prefname
        throw new InvalidPreferenceName("Unable to find preference " + prefName);
    }

    private DefaultJmsListenerContainerFactory getFactoryForQueue(Queue queue) {
        DefaultJmsListenerContainerFactory factory = null;
        switch (queue) {
            case Staging:
                factory = stagingQueueListenerFactory;
                break;
            case Finalizing:
                factory = finalizingQueueListenerFactory;
                break;
        }
        return factory;
    }

    private synchronized void setDesiredPref(QueueBound qb, Integer value) {
        desiredPrefs.put(qb, value);
        markNeedsUpdate(qb.queue);
    }

    private synchronized void markNeedsUpdate(Queue queue) {
        needsUpdate.add(queue);
    }

    private synchronized void clearNeedsUpdate() {
        needsUpdate.clear();
    }

    private synchronized void refreshCache(boolean init) {
        for (Queue queue : Queue.values()) {
            for (Bound bound : Bound.values()) {
                QueueBound qb = new QueueBound(queue, bound);
                Integer value;
                try {
                    value = qb.invokeGetter();
                } catch (UnknownToolId e) {
                    if (init) {
                        // Might be first time this tool is being added to XNAT,
                        // so upon init, it may not have a db row yet
                        value = qb.defaultValue;
                    } else {
                        throw e;
                    }
                }
                desiredPrefs.put(qb, value);
            }
        }
    }

    /**
     * Public-facing method to batch-update preferences
     *
     * Performs validation before saving values / updating factory concurrency
     *
     * @param prefs map of preferences
     * @throws InvalidPreferenceName for unknown preference
     */
    public void setPreferences(Map<String, Integer> prefs) throws InvalidPreferenceName {
        for (final String key : prefs.keySet()) {
            final Integer value = prefs.get(key);
            if (!getIntegerValue(key).equals(value)) {
                setDesiredPref(getQueueBoundFromPrefName(key), value);
            }
        }
        validatePrefsAndUpdateListenerFactories(false);
    }

    /**
     * Validate user-settable preferences (ensure min <= max) and then update prefs in db and update concurrency of factory
     * @throws InvalidPreferenceName for invalid values
     */
    private synchronized void validatePrefsAndUpdateListenerFactories(boolean refresh) throws InvalidPreferenceName {
        if (refresh) {
            // We're refreshing from db
            refreshCache(false); // pull db values into desiredPrefs
            for (Queue queue : Queue.values()) {
                markNeedsUpdate(queue);
            }
        }

        for (Queue queue : needsUpdate) {
            QueueBound qbMin = new QueueBound(queue, Bound.Min);
            QueueBound qbMax = new QueueBound(queue, Bound.Max);
            Integer min = desiredPrefs.get(qbMin);
            Integer max = desiredPrefs.get(qbMax);

            if (!refresh) {
                // Validate
                if (min > max) {
                    // Invalid, so revert prefs "cache" to values from db and throw exception
                    desiredPrefs.put(qbMin, qbMin.invokeGetter());
                    desiredPrefs.put(qbMax, qbMax.invokeGetter());

                    throw new InvalidPreferenceName("Invalid concurrency values for " + queue.toString() +
                            " queue concurrency preferences. Be sure that min is less than or equal to max " +
                            "(your attempt was min=" + min + " and max=" + max + ").");
                }
            }

            // Update values in db (and in bean, so can't skip on refresh)
            qbMin.invokeSetter(min);
            qbMax.invokeSetter(max);

            // Update factory concurrency
            DefaultJmsListenerContainerFactory factory = getFactoryForQueue(queue);
            factory.setConcurrency(min + "-" + max);
        }

        // Clear needsUpdate regardless of whether update occurred or was rejected
        clearNeedsUpdate();
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

        RefreshQueuePrefs(QueuePrefsBean queuePrefsBean, boolean primaryNode) {
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
                queuePrefsBean.validatePrefsAndUpdateListenerFactories(true);
            } catch (Exception e) {
                log.error("Unable to refresh JMS queue concurrency preference beans.", e);
            }
        }
    }

    private static String makePrefNameFromQueueAndBound(Queue queue, Bound bound) {
        // !!!IMPORTANT!!! Do not change this method without changing the method names, below, and the site-settings.yml
        return "concurrency" + bound.toString() + queue.toString() + "Queue";
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
