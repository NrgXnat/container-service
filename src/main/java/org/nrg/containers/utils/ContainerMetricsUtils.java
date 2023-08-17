package org.nrg.containers.utils;

import lombok.extern.slf4j.Slf4j;
import org.nrg.containers.model.container.auto.Container;
import org.nrg.xnat.micrometer.tags.TaggedCounterWrapper;


@Slf4j
public class ContainerMetricsUtils {

    public  static void updateContainerMetrics(final TaggedCounterWrapper containerCounterMetricWrapper, final String metricName, final Container container) {
        try {
            containerCounterMetricWrapper.increment(metricName, container.dockerImage());
        } catch(Exception e) {
            log.error("Could not fetch container metric to update ", e);
        }
    }
}
