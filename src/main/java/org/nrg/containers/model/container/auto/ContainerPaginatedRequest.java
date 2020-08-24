package org.nrg.containers.model.container.auto;

import org.nrg.framework.ajax.hibernate.HibernatePaginatedRequest;

public class ContainerPaginatedRequest extends HibernatePaginatedRequest {
    @Override
    public String getDefaultSortColumn() {
        return "timestamp";
    }
}
