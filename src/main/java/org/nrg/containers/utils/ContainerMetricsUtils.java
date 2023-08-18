package org.nrg.containers.utils;

import lombok.extern.slf4j.Slf4j;
import org.nrg.containers.model.container.auto.Container;
import org.nrg.xdat.XDAT;
import org.nrg.xnat.micrometer.tags.TaggedCounterWrapper;


@Slf4j
public class ContainerMetricsUtils {

    public synchronized static void updateContainerMetrics(final String metricName, final Container container) {
        try {
            final TaggedCounterWrapper containerCounterMetricWrapper = XDAT.getContextService().getBean(TaggedCounterWrapper.class);
            containerCounterMetricWrapper.increment(metricName, container.dockerImage());
        } catch(Exception e) {
            log.error("Could not fetch container metric to update ", e);
        }
    }
}
