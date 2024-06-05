package org.nrg.containers.utils;

import com.github.dockerjava.api.command.BuildImageResultCallback;
import com.github.dockerjava.api.model.BuildResponseItem;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class LoggingBuildCallback extends BuildImageResultCallback {
    @Override
    public void onNext(BuildResponseItem item) {
        log.debug("{}", item);
        super.onNext(item);
    }
}
