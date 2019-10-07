package org.nrg.containers.model.xnat;

import org.mockito.Mockito;
import org.nrg.framework.constants.Scope;
import org.nrg.prefs.beans.PreferenceBean;
import org.nrg.prefs.entities.Preference;
import org.nrg.prefs.entities.PreferenceInfo;
import org.nrg.prefs.entities.Tool;
import org.nrg.prefs.exceptions.InvalidPreferenceName;
import org.nrg.prefs.exceptions.UnknownToolId;
import org.nrg.prefs.resolvers.PreferenceEntityResolver;
import org.nrg.prefs.services.NrgPreferenceService;
import org.nrg.prefs.transformers.PreferenceTransformer;

import java.util.*;

import static org.mockito.Mockito.when;

public class FakePrefsService implements NrgPreferenceService {
    private Map<String, String> prefMap;
    private Properties properties;
    private final Tool tool;

    /*
    This class is needed because it's hard to mock the hibernate-backed pref service and support dynamic preference values.
    Currently assumes all requests will be from same toolId, same scope, same entity id...

    It is NOT complete. It works only well enough to support tests for one particular pref bean (QueuePrefsBean). See
    QueueSettingsRestApiTestConfig and QueueSettingsRestApiTest for sample use.
    */

    public FakePrefsService(String toolId, Map<String, Object> inMap) {
        tool = Mockito.mock(Tool.class);
        when(tool.getToolId()).thenReturn(toolId);
        prefMap = new HashMap<>();
        properties = new Properties();
        for (String key : inMap.keySet()) {
            String value = inMap.get(key).toString();
            prefMap.put(key, value);
            properties.setProperty(key, value);
        }
    }

    @Override
    public Tool createTool(PreferenceBean bean) {
        return tool;
    }

    @Override
    public Tool createTool(Tool tool) {
        return this.tool;
    }

    @Override
    public void create(String toolId, String namespacedPropertyId, String value) {
        prefMap.put(namespacedPropertyId, value);
    }

    @Override
    public void create(String toolId, String namespacedPropertyId, Scope scope, String entityId, String value) {
        prefMap.put(namespacedPropertyId, value);
    }

    @Override
    public boolean hasPreference(String toolId, String preference) {
        return prefMap.containsKey(preference);
    }

    @Override
    public boolean hasPreference(String toolId, String preference, Scope scope, String entityId) {
        return prefMap.containsKey(preference);
    }

    @Override
    public Preference getPreference(String toolId, String preference) throws UnknownToolId {
        return new Preference(tool, preference, prefMap.get(preference));
    }

    @Override
    public Preference getPreference(String toolId, String preference, Scope scope, String entityId) throws UnknownToolId {
        return new Preference(tool, preference, scope, entityId, prefMap.get(preference));
    }

    @Override
    public Preference migrate(String toolId, String alias, String preference) throws UnknownToolId {
        return new Preference(tool, alias, prefMap.get(preference));
    }

    @Override
    public Preference migrate(String toolId, String alias, String preference, Scope scope, String entityId) throws UnknownToolId {
        return new Preference(tool, alias, scope, entityId, prefMap.get(preference));
    }

    @Override
    public String getPreferenceValue(String toolId, String preference) throws UnknownToolId {
        return prefMap.get(preference);
    }

    @Override
    public String getPreferenceValue(String toolId, String preference, Scope scope, String entityId) throws UnknownToolId {
        return prefMap.get(preference);
    }

    @Override
    public void setPreferenceValue(String toolId, String preference, String value) throws UnknownToolId, InvalidPreferenceName {
        prefMap.put(preference, value);
    }

    @Override
    public void setPreferenceValue(String toolId, String preference, Scope scope, String entityId, String value) throws UnknownToolId, InvalidPreferenceName {
        prefMap.put(preference, value);
    }

    @Override
    public void deletePreference(String toolId, String preference) throws InvalidPreferenceName {
        prefMap.remove(preference);
    }

    @Override
    public void deletePreference(String toolId, String preference, Scope scope, String entityId) throws InvalidPreferenceName {
        prefMap.remove(preference);
    }

    @Override
    public Set<String> getToolIds() {
        return Collections.singleton(tool.getToolId());
    }

    @Override
    public Set<Tool> getTools() {
        return Collections.singleton(tool);
    }

    @Override
    public Tool getTool(String toolId) {
        return tool;
    }

    @Override
    public Set<String> getToolPropertyNames(String toolId, Scope scope, String entityId) {
        return prefMap.keySet();
    }

    @Override
    public Set<String> getToolPropertyNames(String toolId) {
        return prefMap.keySet();
    }

    @Override
    public Properties getToolProperties(String toolId, Scope scope, String entityId) {
        return properties;
    }

    @Override
    public Properties getToolProperties(String toolId) {
        return properties;
    }

    @Override
    public Properties getToolProperties(String toolId, Scope scope, String entityId, List<String> preferenceNames) {
        Properties prop = new Properties();
        Enumeration e = properties.propertyNames();
        while (e.hasMoreElements()) {
            String key = (String) e.nextElement();
            if (preferenceNames.contains(key)) {
                prop.setProperty(key, properties.getProperty(key));
            }
        }
        return prop;
    }

    @Override
    public Properties getToolProperties(String toolId, List<String> preferenceNames) {
        return getToolProperties(toolId, null, null, preferenceNames);
    }

    @Override
    public void registerResolver(String toolId, PreferenceEntityResolver resolver) {

    }

    @Override
    public PreferenceTransformer getTransformer(PreferenceInfo preferenceInfo) {
        return null;
    }
}
