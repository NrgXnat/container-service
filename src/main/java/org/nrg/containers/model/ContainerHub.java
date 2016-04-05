package org.nrg.containers.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.base.MoreObjects;
import org.apache.commons.lang.StringUtils;

import java.util.Objects;


@JsonIgnoreProperties({"key"})
public class ContainerHub {
    @JsonProperty("name") private String name;
    @JsonProperty("url") private String url;
    @JsonProperty("username") private String username;
    @JsonProperty("password") private String password;
    @JsonProperty("email") private String email;

    public ContainerHub() {}

    private ContainerHub(final Builder builder) {
        this.name = builder.name;
        this.url = builder.url;
        this.username = builder.username;
        this.password = builder.password;
        this.email = builder.email;
    }

    public static Builder builder() {
        return new Builder();
    }

    public Builder toBuilder() {
        return new Builder(this);
    }

    public String name() {
        return name;
    }

    public String url() {
        return url;
    }

    public String getUrl() {
        return url;
    }

    public String username() {
        return username;
    }

    public String password() {
        return password;
    }

    public String email() {
        return email;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }

        ContainerHub that = (ContainerHub) o;

        return Objects.equals(this.name, that.name) &&
                Objects.equals(this.url, that.url) &&
                Objects.equals(this.username, that.username) &&
                Objects.equals(this.password, that.password) &&
                Objects.equals(this.email, that.email);
    }

    @Override
    public int hashCode() {
        return Objects.hash(name, url, username, password, email);
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
            .add("name", name)
            .add("url", url)
            .add("username", username)
            .add("password", StringUtils.isBlank(password) ? "" : "******")
            .add("email", email)
            .toString();
    }

    public static class Builder {
        private String name;
        private String url;
        private String username;
        private String password;
        private String email;

        private Builder() {}

        private Builder(final ContainerHub hub) {
            this.name = hub.name;
            this.url = hub.url;
            this.username = hub.username;
            this.password = hub.password;
            this.email = hub.email;
        }

        public ContainerHub build() {
            return new ContainerHub(this);
        }

        public Builder name(final String name) {
            this.name = name;
            return this;
        }

        public Builder url(final String url) {
            this.url = url;
            return this;
        }

        public Builder username(final String username) {
            this.username = username;
            return this;
        }

        public Builder password(final String password) {
            this.password = password;
            return this;
        }

        public Builder email(final String email) {
            this.email = email;
            return this;
        }
    }
}
