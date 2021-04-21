package org.nrg.containers.daos;


import lombok.extern.slf4j.Slf4j;
import org.nrg.containers.model.orchestration.entity.OrchestrationEntity;
import org.nrg.framework.constants.Scope;
import org.nrg.framework.orm.hibernate.AbstractHibernateDAO;
import org.springframework.stereotype.Repository;

import javax.annotation.Nullable;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Repository
public class OrchestrationEntityDao extends AbstractHibernateDAO<OrchestrationEntity> {
    /**
     * Returns scoped result or sitewide default
     * @param scope the scope
     * @param scopedItemId the id
     * @return the orchestration entity
     */
    @Nullable
    public OrchestrationEntity findScoped(Scope scope, String scopedItemId) {
        Map<String, Object> properties = new HashMap<>();
        properties.put("scope", scope);
        properties.put("scopedItemId", scopedItemId);
        List<OrchestrationEntity> oes = findByProperties(properties);
        if (oes == null) {
            scope = Scope.Site;
            properties.put("scope", scope);
            properties.put("scopedItemId", null);
            oes = findByProperties(properties);
        }
        if (oes == null) {
            // No orchestration
            return null;
        }
        if (oes.size() > 1) {
            throw new RuntimeException("Multiple " + scope + " orchestration entities");
        }
        return oes.get(0);
    }

    @Nullable
    public OrchestrationEntity findScopedAndEnabled(Scope scope, String scopedItemId) {
        OrchestrationEntity oe = findScoped(scope, scopedItemId);
        return oe == null || !oe.isEnabled() ? null : oe;
    }
}
