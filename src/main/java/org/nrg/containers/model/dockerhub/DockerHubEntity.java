package org.nrg.containers.model.dockerhub;

import com.google.common.base.MoreObjects;
import com.google.common.base.Objects;
import org.apache.commons.lang3.StringUtils;
import org.nrg.framework.orm.hibernate.AbstractHibernateEntity;

import javax.persistence.Column;
import javax.persistence.Entity;

@Entity
public class DockerHubEntity extends AbstractHibernateEntity {

    private String name;
    private String url;
    private String username;
    private String password;
    private String email;
    private String token;

    public static DockerHubEntity fromPojo(final DockerHubBase.DockerHub pojo) {
        return fromPojoWithTemplate(pojo, new DockerHubEntity());
    }

    public static DockerHubEntity fromPojoWithTemplate(final DockerHubBase.DockerHub pojo, final DockerHubEntity template) {
        if (template == null) {
            return fromPojo(pojo);
        }
        template.setId(pojo.id());
        template.name = pojo.name();
        // if url or username has changed, always use new password, even if blank
        if(!StringUtils.equals(template.url, pojo.url()) || !StringUtils.equals(template.username, pojo.username())) {
            template.password = pojo.password();
        } else {
            template.password = StringUtils.isBlank(pojo.password()) ? template.getPassword() : pojo.password();
        }
        template.url = pojo.url();
        template.username = pojo.username();
        template.email = pojo.email();
        template.token = pojo.token();
        return template;
    }

    public DockerHubBase.DockerHub toPojo(final long defaultId) {
        return DockerHubBase.DockerHub.create(this.getId(), this.name, this.url, this.getId() == defaultId,
                this.getUsername(), this.getPassword(), this.getEmail(), this.getToken());
    }

    @Column(unique = true)
    public String getName() {
        return name;
    }

    public void setName(final String name) {
        this.name = name;
    }

    @Column(unique = true)
    public String getUrl() {
        return url;
    }

    public void setUrl(final String url) {
        this.url = url;
    }

    public String getUsername() { return username; }

    public void setUsername(String username) { this.username = username; }

    public String getPassword() { return password; }

    public void setPassword(String password) { this.password = password; }

    public String getEmail() { return email; }

    public void setEmail(String email) { this.email = email; }

    public String getToken() { return token; }

    public void setToken(String token) { this.token = token; }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                .add("name", name)
                .add("url", url)
                .toString();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof DockerHubEntity)) return false;
        if (!super.equals(o)) return false;
        DockerHubEntity that = (DockerHubEntity) o;
        return Objects.equal(name, that.name) &&
                Objects.equal(url, that.url) &&
                Objects.equal(username, that.username) &&
                Objects.equal(password, that.password) &&
                Objects.equal(email, that.email) &&
                Objects.equal(token, that.token);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(super.hashCode(), name, url, username, password, email, token);
    }
}
