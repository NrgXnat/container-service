package org.nrg.containers.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.base.Objects;

public class ContainerServer {
    @JsonProperty("host") private String host;
    @JsonProperty("certPath") private String certPath;

    public ContainerServer() {}

    public ContainerServer(final String host, final String certPath) {
        this.host = host;
        this.certPath = certPath;
    }

    public static Builder builder() {
        return new Builder();
    }

    public Builder toBuilder() {
        return new Builder(this);
    }

    private ContainerServer(final Builder builder) {
        this.host = builder.host;
        this.certPath = builder.certPath;
    }

    public String host() {
        return host;
    }

    public void host(final String host) {
        this.host = host;
    }

    public String certPath() {
        return certPath;
    }

    public void certPath(final String certPath) {
        this.certPath = certPath;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }

        ContainerServer that = (ContainerServer) o;

        return Objects.equal(this.host, that.host) &&
                Objects.equal(this.certPath, that.certPath);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(host, certPath);
    }

    public static class Builder {
        private String host;
        private String certPath;

        private Builder() {}

        private Builder(final ContainerServer server) {
            this.host = server.host;
            this.certPath = server.certPath;
        }

        public ContainerServer build() {
            return new ContainerServer(this);
        }

        public Builder host(final String host) {
            this.host = host;
            return this;
        }

        public String host() {
            return this.host;
        }

        public Builder certPath(final String certPath) {
            this.certPath = certPath;
            return this;
        }

        public String certPath() {
            return this.certPath;
        }
    }
}
