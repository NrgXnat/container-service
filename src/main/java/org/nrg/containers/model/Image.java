package org.nrg.containers.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.base.MoreObjects;
import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;

import java.util.List;
import java.util.Map;
import java.util.Objects;

@ApiModel(description = "Properties that define an image.")
public class Image {

    private String _name;
    private String _id;
    private List<String> _repoTags;
    private Long _size;
    private Map<String, String> _labels;

    public Image() {}

    public Image(final String name, final String id, Long size, List<String> repoTags, Map<String, String> labels){
        _name = name;
        _id = id;
        _size = size;
        _repoTags = repoTags;
        _labels = labels;
    }

    /**
     * The image's name.
     **/
    @ApiModelProperty(value = "The image's name.")
    @JsonProperty("name")
    public String getName() {
        return _name;
    }

    public void setName(final String name) { _name = name; }

    /**
     * The image's id.
     **/
    @ApiModelProperty(value = "The image's id.")
    @JsonProperty("id")
    public String getId() { return _id; }

    public void setId(final String id) { _id = id; }

    /**
     * The image's size.
     **/
    @ApiModelProperty(value = "The image's size.")
    @JsonProperty("size")
    public Long getSize() { return _size; }

    public void setSize(final Long size) { _size = size; }

    /**
     * The image's repo tags.
     **/
    @ApiModelProperty(value = "The image's repo tags.")
    @JsonProperty("repotags")
    public List<String> getRepoTags() { return _repoTags; }

    public void setRepoTags(final List<String> repoTags) { _repoTags = repoTags; }

    /**
     * Image labels
     **/
    @ApiModelProperty(value = "Image labels")
    @JsonProperty("labels")
    public Map<String, String> getLabels() { return _labels; }

    public void setLabels(final Map<String, String> labels) { _labels = labels; }

    public String toString() {
        return MoreObjects.toStringHelper(this)
                .add("id", _id)
                .add("name", _name)
                .add("size", _size)
                .add("tags", _repoTags)
                .add("labels", _labels)
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

        Image that = (Image) o;

        return Objects.equals(this._name, that._name) &&
                Objects.equals(this._id, that._id) &&
                Objects.equals(this._repoTags, that._repoTags) &&
                Objects.equals(this._size, that._size) &&
                Objects.equals(this._labels, that._labels);
    }

    @Override
    public int hashCode() {
        return Objects.hash(_name, _id, _repoTags, _size, _labels);
    }
}
