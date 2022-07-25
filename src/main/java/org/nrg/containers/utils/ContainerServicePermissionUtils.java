package org.nrg.containers.utils;

import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.nrg.containers.model.command.auto.Command;
import org.nrg.xdat.om.XnatImagescandata;
import org.nrg.xdat.om.XnatImagesessiondata;
import org.nrg.xdat.om.XnatProjectdata;
import org.nrg.xdat.om.XnatSubjectdata;
import org.nrg.xdat.schema.SchemaElement;
import org.nrg.xdat.security.SecurityManager;
import org.nrg.xdat.security.helpers.Permissions;
import org.nrg.xft.exception.ElementNotFoundException;
import org.nrg.xft.exception.XFTInitException;
import org.nrg.xft.schema.Wrappers.GenericWrapper.GenericWrapperElement;
import org.nrg.xft.security.UserI;

import javax.annotation.Nonnull;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

@Slf4j
public class ContainerServicePermissionUtils {
    public final static String CONTEXT_PERMISSION_PLACEHOLDER = UUID.randomUUID().toString();
    public final static WrapperPermission READ_PERMISSION_PLACEHOLDER = WrapperPermission.read(CONTEXT_PERMISSION_PLACEHOLDER);
    public final static WrapperPermission EDIT_PERMISSION_PLACEHOLDER = WrapperPermission.edit(CONTEXT_PERMISSION_PLACEHOLDER);
    private final static int SCAN_PERMISSIONS_CHECK_CACHE_TTL = 10; // seconds

    // Equate "project read" permissions to "subject read" permissions.
    // Neither Permissions.canReadProject nor
    // Permissions.canRead(.., "xnat:projectData/ID", ...) give us exactly what we need:
    // "can this user read the data contained in this project?"
    // Checking if they can read a subject is a good proxy.
    public static final String PROJECT_READ_XML_PATH = XnatSubjectdata.SCHEMA_ELEMENT_NAME + "/project";

    // The only "project edit" operation that commands can do (at time of writing) is create project resources.
    // This permission can be checked using the project's xml path.
    public static final String PROJECT_EDIT_XML_PATH = XnatProjectdata.SCHEMA_ELEMENT_NAME + "/ID";

    // Cache the pairs of (specific, generic) xsiType relationships.
    // If specific is equal to or an instance of generic, return true. Else return false.
    private static final Map<XsiTypePair, Boolean> xsiTypePairCache = new ConcurrentHashMap<>();

    // Cache the actions a user can take on at least one image session type in a project to workaround scan perms
    private static final LoadingCache<UserProjectAction, Boolean> userImageSessionPermissionsCache = CacheBuilder
            .newBuilder()
            .expireAfterWrite(SCAN_PERMISSIONS_CHECK_CACHE_TTL, TimeUnit.SECONDS)
            .build(new CacheLoader<UserProjectAction, Boolean>() {
                @Override
                public Boolean load(@Nonnull UserProjectAction upa) throws ExecutionException {
                    return projectExperimentCountCache.get(upa.project).stream().anyMatch(xsiType -> {
                        try {
                            return xsiTypeEqualToOrInstanceOf(xsiType, XnatImagesessiondata.SCHEMA_ELEMENT_NAME) &&
                                    Permissions.can(upa.user, xsiType + "/project", upa.project, upa.action);
                        } catch (Exception e) {
                            log.error("Could not check {} permissions for user \"{}\" project \"{}\" xsiType \"{}\"",
                                    upa.action, upa.user.getUsername(), upa.project, xsiType, e);
                            return false;
                        }
                    });
                }
            });

    // Cache the session types in a given project
    private static final LoadingCache<String, Set<String>> projectExperimentCountCache = CacheBuilder
            .newBuilder()
            .expireAfterWrite(SCAN_PERMISSIONS_CHECK_CACHE_TTL, TimeUnit.SECONDS)
            .build(new CacheLoader<String, Set<String>>() {
                @Override
                public Set<String> load(@Nonnull String project) {
                    XnatProjectdata projectData = XnatProjectdata.getXnatProjectdatasById(project,
                            null, false);
                    if (projectData == null) {
                        // This shouldn't happen
                        return Collections.emptySet();
                    }
                    return projectData.getExperimentCountByXSIType().keySet();
                }
            });

    /**
     * Verify the user has permission to read + edit everything the wrapper requires.
     * ...or at least put in our best effort to verify that.
     *
     * @param userI The user who wants to run a container
     * @param project The project in which the data feeding the container lives
     * @param context The context for which the user is requesting available command wrappers.
     *                Likely the XSI type of the page they're loading in the UI.
     * @param wrapper The Command Wrapper from which the user wants to run a container
     * @return Whether the user can read all the input types and edit all the output types defined in the wrapper
     */
    public static boolean userHasRequiredPermissions(final UserI userI,
                                                     final String project,
                                                     final String context,
                                                     final Command.CommandWrapper wrapper) {
        final String contextXsiType = resolveXsiType(context);
        if (contextXsiType == null) {
            log.debug("Allowing user \"{}\" to view wrappers in project \"{}\" for context \"{}\"",
                    userI.getUsername(), project, context);
            return true;
        }
        return wrapper.requiredPermissions(contextXsiType).stream()
                .allMatch(wp -> canPerformActionOnXsiType(userI, project, wp, true));
    }

    public static boolean canCreateOutputObject(final UserI userI,
                                                final String project,
                                                final String targetXsiType,
                                                final Command.CommandWrapperOutput outputHandler) {
        final String xsiTypeToEdit = outputHandler.requiredEditPermissionXsiType(targetXsiType);

        if (StringUtils.isBlank(xsiTypeToEdit)) {
            // We can't check if they have permission.
            // It's possible it will fail or work just fine.
            // We default to letting them run.
            log.warn("Output handler \"{}\" will need to edit an item of unknown XSI type. " +
                    "We cannot check permissions beforehand without knowing the XSI type. " +
                    "This could fail after the container has finished running.", outputHandler.name());
            return true;
        }

        return canPerformActionOnXsiType(userI, project, WrapperPermission.edit(xsiTypeToEdit), false);
    }

    public static boolean canReadProject(final UserI userI, final String project) {
        return canPerformActionOnXsiType(userI, project, XnatProjectdata.SCHEMA_ELEMENT_NAME, WrapperPermissionAction.READ, false);
    }

    private static String resolveXsiType(final String xsiType) {
        try {
            // This assumes the context is an xsi type, which it is not always.
            // And even if it is, it might be a generic one like xnat:imageSessionData.
            // In those cases we won't be able to check if they can read it.
            final GenericWrapperElement gwe = GenericWrapperElement.GetElement(xsiType);

            // If we're here, then the xsi type was able to be resolved into an XFT element
            return gwe.getXSIType();
        } catch (Exception e) {
            log.debug("Could not resolve xsiType \"{}\"", xsiType, e);
            // Carry on...
        }
        return null;
    }

    private static boolean canPerformActionOnXsiType(final UserI userI,
                                                     final String project,
                                                     final WrapperPermission wrapperPermission,
                                                     boolean defaultValue) {
        return canPerformActionOnXsiType(userI, project, wrapperPermission.xsiType, wrapperPermission.action, defaultValue);
    }

    private static boolean canPerformActionOnXsiType(final UserI userI,
                                                     final String project,
                                                     String xsiType,
                                                     final WrapperPermissionAction action,
                                                     boolean defaultValue) {
        xsiType = resolveXsiType(xsiType);

        boolean can = defaultValue;
        if (xsiType != null) {
            // Default xml path for most data types
            String xmlPathToCheck = xsiType + "/project";
            String xdatPermissionsAction = action.getXdatPermissionsAction();

            // Have to do project checks a different way from everything else
            if (xsiType.equals(XnatProjectdata.SCHEMA_ELEMENT_NAME)) {
                switch (action) {
                    case READ:
                        xmlPathToCheck = PROJECT_READ_XML_PATH;
                        break;
                    case EDIT:
                        xmlPathToCheck = PROJECT_EDIT_XML_PATH;
                        break;
                }
            } else if (xsiTypeEqualToOrInstanceOf(xsiType, XnatImagescandata.SCHEMA_ELEMENT_NAME)) {
                // CS-755 Handle scans differently until XXX-187
                try {
                    return scanPermissionCheckWorkaround(userI, project, xdatPermissionsAction);
                } catch (ExecutionException e) {
                    log.error("Could not perform scan permissions workaround checking {} permissions for user \"{}\" " +
                                    "project \"{}\" xsiType \"{}\"", action, userI.getUsername(), project, xsiType, e);
                    return defaultValue;
                }
            }

            try {
                can = Permissions.can(userI, xmlPathToCheck, project, xdatPermissionsAction);
            } catch (Exception e) {
                log.error("Could not check {} permissions for user \"{}\" project \"{}\" xsiType \"{}\"",
                        action, userI.getUsername(), project, xsiType, e);
                // Carry on...
            }
        }
        log.debug("User \"{}\" can{} {} type \"{}\" in project \"{}\"",
                userI.getUsername(), can ? "" : "not", action, xsiType, project);
        return can;
    }

    /**
     * Until XXX-187, we use the parent session when checking scan permissions. However, when we just have a scan
     * xsi type, we don't have a means of determining the session (scans of any type can belong to sessions of any type).
     *
     * Workaround here checks if the user has permission to perform the required scan action on at least one session
     * type in the project. This is not perfect, but it handles some critical common cases (collaborators, public projects)
     * and also read-only custom user groups. It does NOT prevent false positives in cases where the user can
     * read/edit <em>some</em> session type but not the particular session type that owns the scan.
     *
     * @param userI the user
     * @param project the project
     * @param xdatPermissionsAction the action
     * @return whether user has permission to perform the action on at least one session type in the project
     * @throws ExecutionException for caching issues
     */
    private static boolean scanPermissionCheckWorkaround(UserI userI, String project, String xdatPermissionsAction)
            throws ExecutionException {
        return userImageSessionPermissionsCache.get(new UserProjectAction(userI, project, xdatPermissionsAction));
    }

    /**
     * Check if the xsiType that the user gave us is equal to *or* *descended* *from*
     * one of the xsiTypes in the wrapper's contexts set.
     *
     * Example
     * If a wrapper can run on {"xnat:mrSessionData", "xnat:petSessionData"}, and
     * the user asks 'what can I run on an "xnat:mrSessionData"?' we return true.
     *
     * If a wrapper can run on {"xnat:imageSessionData", "xnat:imageAssessorData"}, and
     * the user asks 'what can I run on an "xnat:mrSessionData"?' we return true.

     * If a wrapper can run on {"xnat:mrSessionData"}, and
     * the user asks 'what can I run on an "xnat:imageSessionData"?' we return false.
     *
     * Check if an input specific XSI type is equal to *or* an instance of
     * another (potentially generic) XSI type.
     *
     * Examples
     * We return true if the XSI types are equal
     * xsiTypesMatch("xnat:mrSessionData", "xnat:mrSessionData") == true
     *
     * We return true if the first argument, specificXsiType, is an instance of the second argument
     * xsiTypesMatch("xnat:mrSessionData", "xnat:imageSessionData") == true
     *
     * However if the first argument is a generic type, we return false
     * xsiTypesMatch("xnat:imageSessionData", "xnat:mrSessionData") == false

     *
     * @param concreteXsiType A concrete XSI type
     * @param potentiallyGenericXsiTypes A set of XSI types which are possibly a generic type or possibly a specific type
     * @return Can this wrapper run on this xsiType?
     */
    public static boolean xsiTypeEqualToOrInstanceOf(final @Nonnull String concreteXsiType,
                                                     final @Nonnull Set<String> potentiallyGenericXsiTypes) {
        if (potentiallyGenericXsiTypes.contains(concreteXsiType)) {
            return true;
        }
        return potentiallyGenericXsiTypes.stream()
                .anyMatch(wrapperXsiType -> xsiTypeEqualToOrInstanceOf(concreteXsiType, wrapperXsiType));
    }

    /**
     * Check if an input specific XSI type is equal to *or* an instance of
     * another (potentially generic) XSI type.
     *
     * Examples
     * We return true if the XSI types are equal
     * xsiTypesMatch("xnat:mrSessionData", "xnat:mrSessionData") == true
     *
     * We return true if the first argument, specificXsiType, is an instance of the second argument
     * xsiTypesMatch("xnat:mrSessionData", "xnat:imageSessionData") == true
     *
     * However if the first argument is a generic type, we return false
     * xsiTypesMatch("xnat:imageSessionData", "xnat:mrSessionData") == false
     *
     * @param concreteXsiType A concrete XSI type
     * @param potentiallyGenericXsiType An XSI type which is possibly a generic type or possibly a specific type
     * @return Is
     */
    public static boolean xsiTypeEqualToOrInstanceOf(final String concreteXsiType, final String potentiallyGenericXsiType) {
        return xsiTypeEqualToOrInstanceOf(new XsiTypePair(concreteXsiType, potentiallyGenericXsiType));
    }

    private static boolean xsiTypeEqualToOrInstanceOf(final XsiTypePair xsiTypePair) {
        return xsiTypePairCache.computeIfAbsent(xsiTypePair, ContainerServicePermissionUtils::computeXsiTypePairInstanceOf);
    }

    private static boolean computeXsiTypePairInstanceOf(final XsiTypePair xsiTypePair) {
        if (xsiTypePair.eitherIsNull()) {
            return false;
        }

        if (xsiTypePair.equalPair()) {
            return true;
        }

        try {
            return SchemaElement.GetElement(xsiTypePair.concreteXsiType)
                    .getGenericXFTElement()
                    .instanceOf(xsiTypePair.potentiallyGenericXsiType);
        } catch (XFTInitException e) {
            log.error("XFT not initialized."); // If this happens, we have a lot of other problems.
        } catch (ElementNotFoundException e) {
            // I was treating this as an error. Now I want to log it and move on.
            // This will allow users to set whatever they want as the context and request it by name.
            //      - JF 2017-09-28
            log.debug("Did not find XSI type \"{}\".", xsiTypePair.concreteXsiType);
        }
        return false;
    }

    public enum WrapperPermissionAction {
        READ(SecurityManager.READ),
        EDIT(SecurityManager.EDIT);

        private final String action;

        WrapperPermissionAction(final String action) {
            this.action = action;
        }

        public String getXdatPermissionsAction() {
            return action;
        }

        @Override
        public String toString() {
            return name().toLowerCase();
        }
    }

    public final static class WrapperPermission {
        final WrapperPermissionAction action;

        final String xsiType;

        WrapperPermission(WrapperPermissionAction action, String xsiType) {
            this.action = action;
            this.xsiType = xsiType;
        }

        public static WrapperPermission read(final String xsiType) {
            return new WrapperPermission(WrapperPermissionAction.READ, xsiType);
        }

        public static WrapperPermission edit(final String xsiType) {
            return new WrapperPermission(WrapperPermissionAction.EDIT, xsiType);
        }

        public WrapperPermissionAction getAction() {
            return action;
        }

        public String getXsiType() {
            return xsiType;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            WrapperPermission that = (WrapperPermission) o;
            return Objects.equals(action, that.action) && Objects.equals(xsiType, that.xsiType);
        }

        @Override
        public int hashCode() {
            return Objects.hash(action, xsiType);
        }

        @Override
        public String toString() {
            return "WrapperPermission{\"" + action + "\", \"" + xsiType + "\"}";
        }
    }

    private static class XsiTypePair {
        private final String concreteXsiType;
        private final String potentiallyGenericXsiType;

        XsiTypePair(final String concreteXsiType, final String potentiallyGenericXsiType) {
            this.concreteXsiType = concreteXsiType;
            this.potentiallyGenericXsiType = potentiallyGenericXsiType;
        }

        boolean eitherIsNull() {
            return concreteXsiType == null || potentiallyGenericXsiType == null;
        }

        boolean equalPair() {
            return concreteXsiType != null && concreteXsiType.equals(potentiallyGenericXsiType);
        }

        @Override
        public boolean equals(final Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            final XsiTypePair that = (XsiTypePair) o;
            return Objects.equals(this.concreteXsiType, that.concreteXsiType)
                    && Objects.equals(this.potentiallyGenericXsiType, that.potentiallyGenericXsiType);
        }

        @Override
        public int hashCode() {
            return Objects.hash(concreteXsiType, potentiallyGenericXsiType);
        }
    }

    private static class UserProjectAction {
        final UserI user;
        final String project;
        final String action;

        public UserProjectAction(UserI user, String project, String action) {
            this.user = user;
            this.project = project;
            this.action = action;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            UserProjectAction that = (UserProjectAction) o;
            return Objects.equals(user.getUsername(), that.user.getUsername()) &&
                    Objects.equals(project, that.project) &&
                    Objects.equals(action, that.action);
        }

        @Override
        public int hashCode() {
            return Objects.hash(user.getUsername(), project, action);
        }
    }
}
