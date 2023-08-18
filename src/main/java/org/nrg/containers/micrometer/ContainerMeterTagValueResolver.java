package org.nrg.containers.micrometer;

import io.micrometer.common.annotation.ValueResolver;
import lombok.extern.slf4j.Slf4j;
import org.nrg.containers.model.container.auto.Container;

@Slf4j
public class ContainerMeterTagValueResolver implements ValueResolver {

    @Override
    public String resolve(Object parameter) {
        if (parameter instanceof  Container) {
            return ((Container)parameter).dockerImage();
        }
        return parameter.toString();
    }
}
