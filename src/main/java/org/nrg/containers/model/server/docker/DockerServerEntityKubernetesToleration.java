package org.nrg.containers.model.server.docker;

import com.fasterxml.jackson.annotation.JsonIgnore;

import javax.persistence.*;
import java.io.Serializable;
import java.util.Objects;

@Entity
@Table(uniqueConstraints = {@UniqueConstraint(columnNames = {"toleration_key", "operator", "effect", "docker_server_entity"})})
public class DockerServerEntityKubernetesToleration implements Serializable {
    private static final long serialVersionUID = 1L;

    private long id;
    @JsonIgnore
    private DockerServerEntity dockerServerEntity;
    private String tolerationKey;
    private String operator;
    private String value;
    private String effect;

    public static DockerServerEntityKubernetesToleration fromPojo(final DockerServerBase.KubernetesToleration pojo) {
        final DockerServerEntityKubernetesToleration entity = new DockerServerEntityKubernetesToleration();
        entity.update(pojo);
        return entity;
    }

    public DockerServerEntityKubernetesToleration update(final DockerServerBase.KubernetesToleration pojo) {
        this.setId(pojo.id());
        this.setTolerationKey(pojo.key());
        this.setOperator(pojo.operator());
        this.setValue(pojo.value());
        this.setEffect(pojo.effect());
        this.setDockerServerEntity(dockerServerEntity);
        return this;
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public long getId() {
        return id;
    }

    public void setId(final long id) {
        this.id = id;
    }

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "docker_server_entity")
    public DockerServerEntity getDockerServerEntity() {
        return dockerServerEntity;
    }

    public void setDockerServerEntity(final DockerServerEntity dockerServerEntity) {
        this.dockerServerEntity = dockerServerEntity;
    }

    @Column(name = "toleration_key")
    public String getTolerationKey() {
        return tolerationKey;
    }

    public void setTolerationKey(final String tolerationKey) {
        this.tolerationKey = tolerationKey;
    }

    public String getOperator() {
        return operator;
    }

    public void setOperator(final String operator) {
        this.operator = operator;
    }

    public String getValue() {
        return value;
    }

    public void setValue(final String value) {
        this.value = value;
    }

    public String getEffect() {
        return effect;
    }

    public void setEffect(final String effect) {
        this.effect = effect;
    }

    @Override
    public boolean equals(final Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        final DockerServerEntityKubernetesToleration that = (DockerServerEntityKubernetesToleration) o;
        return Objects.equals(this.tolerationKey, that.tolerationKey) &&
                Objects.equals(this.operator, that.operator) &&
                Objects.equals(this.value, that.value) &&
                Objects.equals(this.effect, that.effect);
    }

    @Override
    public int hashCode() {
        return Objects.hash(tolerationKey, operator, value, effect);
    }
}
