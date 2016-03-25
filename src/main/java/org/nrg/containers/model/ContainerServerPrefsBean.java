package org.nrg.containers.model;

import com.google.common.base.MoreObjects;
import com.google.common.base.Objects;
import org.nrg.prefs.annotations.NrgPreference;
import org.nrg.prefs.annotations.NrgPreferenceBean;
import org.nrg.prefs.beans.AbstractPreferenceBean;
import org.nrg.prefs.exceptions.InvalidPreferenceName;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@NrgPreferenceBean(toolId = "container-server",
    toolName = "Container Server Prefs",
    description = "All the preferences that define a Container Server")
public class ContainerServerPrefsBean extends AbstractPreferenceBean {
    private static final Logger _log = LoggerFactory.getLogger(ContainerServerPrefsBean.class);

    public void setFromBean(final ContainerServer csBean) throws InvalidPreferenceName {
        setHost(csBean.host());
        setCertPath(csBean.certPath());
    }

    public ContainerServer toBean() {
        return ContainerServer.builder()
            .host(getHost())
            .certPath(getCertPath())
            .build();
    }

    @NrgPreference
    public String getHost() {
        return getValue("host");
    }

    public void setHost(final String host) throws InvalidPreferenceName {
        _log.debug("Setting host: " + host);
        if (host != null) {
            set(host, "host");
        }
    }

    @NrgPreference
    public String getCertPath() {
        return getValue("certPath");
    }

    public void setCertPath(final String certPath) throws InvalidPreferenceName {
        _log.debug("Setting certPath: " + certPath);
        if (certPath != null) {
            set(certPath, "certPath");
        }
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
            .add("host", getHost())
            .add("certPath", getCertPath())
            .toString();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }

        ContainerServerPrefsBean that = (ContainerServerPrefsBean) o;

        return Objects.equal(this.getHost(), that.getHost()) &&
                Objects.equal(this.getCertPath(), that.getCertPath());
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(getHost(), getCertPath());
    }
}
