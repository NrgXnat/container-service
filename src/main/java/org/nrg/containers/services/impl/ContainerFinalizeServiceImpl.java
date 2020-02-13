package org.nrg.containers.services.impl;

import static org.nrg.containers.model.command.entity.CommandWrapperOutputEntity.Type.ASSESSOR;
import static org.nrg.containers.model.command.entity.CommandWrapperOutputEntity.Type.RESOURCE;

import java.io.*;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.annotation.Nullable;

import com.spotify.docker.client.messages.swarm.TaskStatus;
import org.apache.commons.io.FilenameUtils;
import org.apache.commons.lang3.StringUtils;
import org.nrg.action.ClientException;
import org.nrg.containers.api.ContainerControlApi;
import org.nrg.containers.exceptions.ContainerException;
import org.nrg.containers.exceptions.DockerServerException;
import org.nrg.containers.exceptions.NoDockerServerException;
import org.nrg.containers.exceptions.UnauthorizedException;
import org.nrg.containers.jms.requests.ContainerRequest;
import org.nrg.containers.model.command.entity.CommandType;
import org.nrg.containers.model.command.entity.CommandWrapperOutputEntity;
import org.nrg.containers.model.container.auto.Container;
import org.nrg.containers.model.container.auto.Container.ContainerMount;
import org.nrg.containers.model.container.auto.Container.ContainerOutput;
import org.nrg.containers.services.ContainerFinalizeService;
import org.nrg.containers.services.ContainerService;
import org.nrg.containers.utils.ContainerUtils;
import org.nrg.mail.services.MailService;
import org.nrg.xdat.om.XnatExperimentdata;
import org.nrg.xdat.om.XnatProjectdata;
import org.nrg.xdat.om.XnatResourcecatalog;
import org.nrg.xdat.om.XnatSubjectdata;
import org.nrg.xdat.preferences.SiteConfigPreferences;
import org.nrg.xdat.security.helpers.Permissions;
import org.nrg.xft.XFTItem;
import org.nrg.xft.event.persist.PersistentWorkflowI;
import org.nrg.xft.event.persist.PersistentWorkflowUtils;
import org.nrg.xft.security.UserI;
import org.nrg.xft.utils.FileUtils;
import org.nrg.xnat.helpers.uri.URIManager;
import org.nrg.xnat.helpers.uri.UriParserUtils;
import org.nrg.xnat.restlet.util.XNATRestConstants;
import org.nrg.xnat.services.archive.CatalogService;
import org.nrg.xnat.turbine.utils.ArchivableItem;
import org.nrg.xnat.utils.WorkflowUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.google.common.base.Function;
import com.google.common.base.Joiner;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;

import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
public class ContainerFinalizeServiceImpl implements ContainerFinalizeService {

    private final ContainerControlApi containerControlApi;
    private final SiteConfigPreferences siteConfigPreferences;
    private final CatalogService catalogService;
    private final MailService mailService;

    private final Pattern experimentUri = Pattern.compile("^(/archive)?/experiments/([^/]+)$");

    @Autowired
    public ContainerFinalizeServiceImpl(final ContainerControlApi containerControlApi,
                                        final SiteConfigPreferences siteConfigPreferences,
                                        final CatalogService catalogService,
                                        final MailService mailService) {
        this.containerControlApi = containerControlApi;
        this.siteConfigPreferences = siteConfigPreferences;
        this.catalogService = catalogService;
        this.mailService = mailService;
    }

    @Override
    public Container finalizeContainer(final Container toFinalize, final UserI userI, final boolean isFailed, final List<Container> wrapupContainers) {
        final ContainerFinalizeHelper helper =
                new ContainerFinalizeHelper(toFinalize, userI, isFailed, wrapupContainers);
        return helper.finalizeContainer();
    }

    @Override
    public void sendContainerStatusUpdateEmail(UserI userI,
                                               boolean completionStatus,
                                               String pipelineName,
                                               String xnatId,
                                               String xnatLabel,
                                               String project,
                                               @Nullable List<String> filePaths) {
        String admin = siteConfigPreferences.getAdminEmail();
        String status = completionStatus ? "Completed" : "Failed";
        String subject = pipelineName + " update: " + status + " processing of " +
                (xnatLabel !=null ? xnatLabel : xnatId) + " in project " + project;
        Map<String, File> attachments = new HashMap<String, File>();
        if (filePaths != null) {
            for (String fPath : filePaths) {
                File f = new File(fPath);
                if (f.exists() && f.isFile()) {
                    attachments.put(f.getName(), f);
                }
            }
        }
        boolean hasAttachments = false;
        if (attachments.size() > 0) {
            hasAttachments = true;
        }
        String emailHTMLBody = composeHTMLBody(pipelineName, status, xnatId, xnatLabel, project, hasAttachments);
        String emailText = composeEmailText(pipelineName, status, xnatId, xnatLabel, project, hasAttachments);

        try {
            mailService.sendHtmlMessage(admin, new String[]{userI.getEmail()}, new String[]{admin},null, subject, emailHTMLBody, emailText, attachments);
        } catch (Exception exception) {
            log.error("Send failed. Retrying by sending each email individually.", exception);
            int successfulSends = 0;
            try {
                mailService.sendHtmlMessage(admin, new String[]{admin}, null, null, subject, emailHTMLBody, emailText, attachments);
                successfulSends++;
            } catch (Exception e) {
                log.error("Unable to send mail to " + admin + ".", e);
            }
            if (successfulSends == 0) {
                log.error("Unable to send mail", exception);
            }
        }
    }

    private String composeHTMLBody(String pipeline_name,String status,String xnatId, String xnatLabel, String project, boolean hasAttachments) {
        String htmlTxt  = "";
        StringBuilder sb = new StringBuilder();
        sb.append("<html>");
        sb.append("<body>");
        sb.append(pipeline_name + " processing for " + (xnatLabel==null?xnatId:xnatLabel) +" in project " + project + " has " + status.toLowerCase());
        if (hasAttachments)
            sb.append("<br/> Log files generated by the processing are attached.");
        sb.append("</body>");
        sb.append("</html>");
        htmlTxt = sb.toString();
        return htmlTxt;
    }

    private String composeEmailText(String pipeline_name,String status,String xnatId, String xnatLabel, String project, boolean hasAttachments) {
        String txt  = "";
        txt = pipeline_name + " processing for " + (xnatLabel==null?xnatId:xnatLabel) +" has " + status.toLowerCase();
        if (hasAttachments)
            txt += "Log files generated by the processing are attached.";
        return txt;
    }

    private class ContainerFinalizeHelper {

        private Container toFinalize;
        private UserI userI;
        // private String exitCode;
        private boolean isFailed;

        private Map<String, ContainerMount> outputMounts;

        private String prefix;

        private Map<String, Container> wrapupContainerMap;

        private Map<String, String> wrapperInputAndOutputValues;

        private ContainerFinalizeHelper(final Container toFinalize,
                                        final UserI userI,
                                        final boolean isFailed,
                                        final List<Container> wrapupContainers) {
            this.toFinalize = toFinalize;
            this.userI = userI;
            this.isFailed = isFailed;

            outputMounts = Maps.newHashMap();

            prefix = "Container " + toFinalize.databaseId() + ": ";

            if (wrapupContainers == null || wrapupContainers.size() == 0) {
                wrapupContainerMap = Collections.emptyMap();
            } else {
                wrapupContainerMap = new HashMap<>();
                for (final Container wrapupContainer : wrapupContainers) {
                    wrapupContainerMap.put(wrapupContainer.parentSourceObjectName(), wrapupContainer);
                }
            }

            // Pre-populate the map of wrapper input and output values with the inputs.
            // Output URI values will be added as we create them here.
            wrapperInputAndOutputValues = new HashMap<>(toFinalize.getWrapperInputs());
        }

        private Container finalizeContainer() {
            final Container.Builder finalizedContainerBuilder = toFinalize.toBuilder();
            List<String> logPaths = uploadLogs();
            finalizedContainerBuilder.logPaths(logPaths);
            String workFlowId = toFinalize.workflowId();
            PersistentWorkflowI wrkFlow = (StringUtils.isNotBlank(workFlowId)) ?
                    WorkflowUtils.getUniqueWorkflow(userI, workFlowId) : null;
            String xnatLabel = null;
            String xnatId = null;
            String project = null;
            String pipeline_name = null;
            Integer eventId = null;
            if (wrkFlow != null) {
                xnatId = wrkFlow.getId();
                project   = wrkFlow.getExternalid();
                pipeline_name = wrkFlow.getPipelineName();
                eventId = wrkFlow.buildEvent().getEventId().intValue();
                try {
                    XnatExperimentdata exp = XnatExperimentdata.getXnatExperimentdatasById(xnatId, userI, false);
                    xnatLabel = exp != null ? exp.getLabel() : null;
                	if (xnatLabel == null){
                    	XnatSubjectdata sub = XnatSubjectdata.getXnatSubjectdatasById(xnatId, userI, false);
                        xnatLabel = sub != null ? sub.getLabel() : null;
                	}
                	if (xnatLabel == null) {
                	    XnatProjectdata proj = XnatProjectdata.getProjectByIDorAlias(xnatId, userI, false);
                        xnatLabel = proj != null ? proj.getId() : null;
                    }
                } catch(Exception e) {
                	log.error("Unable to get the XNAT Label for " + xnatId);
                }
            }

            String status = null;
            String details = "";
            boolean processingCompleted = !isFailed;

            if (processingCompleted) {
                // Upload outputs if processing completed successfully
                for (final ContainerMount mountOut : toFinalize.mounts()) {
                    outputMounts.put(mountOut.name(), mountOut);
                }

                final OutputsAndExceptions outputsAndExceptions = uploadOutputs(eventId);
                final List<Exception> failedRequiredOutputs = outputsAndExceptions.exceptions;
                status = PersistentWorkflowUtils.COMPLETE;
                Date statusTime = new Date();
                if (!failedRequiredOutputs.isEmpty()) {
                    details = "Failed to upload required outputs.\n" + Joiner.on("\n").join(Lists.transform(failedRequiredOutputs, new Function<Exception, String>() {
                        @Override
                        public String apply(final Exception input) {
                            return input.getMessage();
                        }
                    }));
                    final Container.ContainerHistory failedHistoryItem = Container.ContainerHistory.fromSystem(PersistentWorkflowUtils.FAILED + " (Upload)",
                            details);
                    status = failedHistoryItem.status();
                    statusTime = failedHistoryItem.timeRecorded();
                    finalizedContainerBuilder.addHistoryItem(failedHistoryItem)
                            .statusTime(failedHistoryItem.timeRecorded());
                }
                finalizedContainerBuilder.outputs(outputsAndExceptions.outputs)  // Overwrite any existing outputs
                        .status(status)
                        .statusTime(statusTime);
            } else {
                // Check if failure already recorded (perhaps with more detail so we don't want to overwrite)
                for (Container.ContainerHistory history : toFinalize.history()) {
                    String containerStatus = history.status();
                    containerStatus = containerStatus != null ?
                            containerStatus.replaceAll("^" + ContainerRequest.inQueueStatusPrefix, "")
                                    .replaceFirst(ContainerServiceImpl.WAITING + " \\(([^)]*)\\)", "$1")
                            : "";
                    if (containerStatus.startsWith(PersistentWorkflowUtils.FAILED)) {
                        status = containerStatus;
                    } else if ((containerStatus.equals(TaskStatus.TASK_STATE_FAILED) || containerStatus.equals("die")) &&
                            StringUtils.isNotBlank(history.message())) {
                        details = history.message();
                    }
                }
                if (status == null || !status.startsWith(PersistentWorkflowUtils.FAILED)) {
                    // If it's not an XNAT failure status, we need to make it so
                    status = PersistentWorkflowUtils.FAILED;
                }
                finalizedContainerBuilder.addHistoryItem(Container.ContainerHistory.fromSystem(status, details));
                finalizedContainerBuilder.status(status)
                        .statusTime(new Date());

                if (StringUtils.isBlank(details)) {
                    details = "Non-zero exit code and/or failure status from container";
                }
            }

            ContainerUtils.updateWorkflowStatus(toFinalize.workflowId(), status, userI, details);
            if (!processingCompleted || CommandType.DOCKER.getName().equals(toFinalize.subtype())) {
                // only send emails for the parent container or if processing failed
                sendContainerStatusUpdateEmail(userI, processingCompleted, pipeline_name,
                        xnatId, xnatLabel, project, logPaths);
            }

            return finalizedContainerBuilder.build();
        }

        private List<String> uploadLogs() {
            log.info(prefix + "Getting logs.");
            final List<String> logPaths = Lists.newArrayList();

            final String stdoutLogStr = getStdoutLogStr();
            final String stderrLogStr = getStderrLogStr();

            if (StringUtils.isNotBlank(stdoutLogStr) || StringUtils.isNotBlank(stderrLogStr)) {

                final String archivePath = siteConfigPreferences.getArchivePath(); // TODO find a place to upload this thing. Root of the archive if sitewide, else under the archive path of the root object
                if (StringUtils.isNotBlank(archivePath)) {
                    final String subtype = StringUtils.defaultIfBlank(toFinalize.subtype(), "");
                    final SimpleDateFormat formatter = new SimpleDateFormat(XNATRestConstants.PREARCHIVE_TIMESTAMP);
                    final String datestamp = formatter.format(new Date());
                    final String containerExecPath = FileUtils.AppendRootPath(archivePath, "CONTAINER_EXEC/");
                    final String destinationPath = containerExecPath + datestamp + "/LOGS/" + subtype;
                    final File destination = new File(destinationPath);
                    destination.mkdirs();

                    log.info(prefix + "Saving logs to " + destinationPath);

                    if (StringUtils.isNotBlank(stdoutLogStr)) {
                        log.debug("Saving stdout");
                        final File stdoutFile = new File(destination, ContainerService.STDOUT_LOG_NAME);
                        FileUtils.OutputToFile(stdoutLogStr, stdoutFile.getAbsolutePath());
                        logPaths.add(stdoutFile.getAbsolutePath());
                    } else {
                        log.debug("Stdout was blank");
                    }

                    if (StringUtils.isNotBlank(stderrLogStr)) {
                        log.debug("Saving stderr");
                        final File stderrFile = new File(destination, ContainerService.STDERR_LOG_NAME);
                        FileUtils.OutputToFile(stderrLogStr, stderrFile.getAbsolutePath());
                        logPaths.add(stderrFile.getAbsolutePath());
                    } else {
                        log.debug("Stderr was blank");
                    }
                }
            }

            log.debug("Adding log paths to container");
            return logPaths;
        }

        private String getStderrLogStr() {
            try {
                return containerControlApi.getStderrLog(toFinalize);
            } catch (DockerServerException | NoDockerServerException e) {
                log.error(prefix + "Could not get stderr log.", e);
            }
            return null;
        }

        private String getStdoutLogStr() {
            try {
                return containerControlApi.getStdoutLog(toFinalize);
            } catch (DockerServerException | NoDockerServerException e) {
                log.error(prefix + "Could not get stdout log.", e);
            }
            return null;
        }

        private OutputsAndExceptions uploadOutputs(@Nullable Integer uploadEventId) {
            log.info(prefix + "Uploading outputs.");

            final List<ContainerOutput> outputs = Lists.newArrayList();
            final List<Exception> exceptions = Lists.newArrayList();
            for (final ContainerOutput nonUploadedOuput: toFinalize.getOrderedOutputs()) {
                try {
                    outputs.add(uploadOutput(nonUploadedOuput, uploadEventId));
                } catch (UnauthorizedException | ContainerException | RuntimeException e) {
                    log.error("Cannot upload files for command output " + nonUploadedOuput.name(), e);
                    if (nonUploadedOuput.required()) {
                        exceptions.add(e);
                    }
                    outputs.add(nonUploadedOuput);
                }
            }

            log.info(prefix + "Done uploading outputs.");
            return new OutputsAndExceptions(outputs, exceptions);
        }

        private ContainerOutput uploadOutput(final ContainerOutput output, @Nullable Integer uploadEventId)
                throws ContainerException, UnauthorizedException {
            log.info(prefix + "Uploading output \"{}\".", output.name());
            log.debug("{}", output);

            final String mountXnatHostPath;
            final String viaWrapupContainer = output.viaWrapupContainer();
            if (StringUtils.isBlank(viaWrapupContainer)) {
                final String mountName = output.mount();
                final ContainerMount mount = getMount(mountName);
                if (mount == null) {
                    throw new ContainerException(String.format(prefix + "Mount \"%s\" does not exist.", mountName));
                }

                log.debug(prefix + "Output files are provided by mount \"{}\": {}", mountName, mount);
                mountXnatHostPath = mount.xnatHostPath();
            } else {
                log.debug(prefix + "Output files are provided by wrapup container \"{}\".", viaWrapupContainer);
                final Container wrapupContainer = getWrapupContainer(output.name());
                if (wrapupContainer == null) {
                    throw new ContainerException(prefix + "Container output \"" + output.name() + "\" " +
                            "must be processed via wrapup container \"" + viaWrapupContainer + "\" which was not found.");
                }

                ContainerMount wrapupOutputMount = null;
                for (final ContainerMount mount : wrapupContainer.mounts()) {
                    if (mount.name().equals("output")) {
                        wrapupOutputMount = mount;
                    }
                }
                if (wrapupOutputMount == null) {
                    throw new ContainerException(prefix + "Container output \"" + output.name() + "\" " +
                            "was processed via wrapup container \"" + wrapupContainer.databaseId() + "\" which has no output mount.");
                }

                mountXnatHostPath = wrapupOutputMount.xnatHostPath();
            }

            if (StringUtils.isBlank(mountXnatHostPath)) {
                throw new ContainerException(String.format(prefix + "Cannot upload output \"%s\". The path to the files on the XNAT machine is blank.", output.name()));
            }

            final String relativeFilePath = output.path() != null ? output.path() : "";
            final String filePath = StringUtils.isBlank(relativeFilePath) ? mountXnatHostPath :
                    FilenameUtils.concat(mountXnatHostPath, relativeFilePath);
            final String globMatcher = output.glob() != null ? output.glob() : "";

            final List<File> toUpload = new ArrayList<>(matchGlob(filePath, globMatcher));
            if (toUpload.size() == 0) {
                // The glob matched nothing. But we could still upload the root path
                toUpload.add(new File(filePath));
            }

            final String label = StringUtils.isNotBlank(output.label()) ? output.label() : output.name();

            String parentUri = getUriByInputOrOutputHandlerName(output.handledBy());
            if (parentUri == null) {
                throw new ContainerException(String.format(prefix + "Cannot upload output \"%s\". Could not instantiate object from input \"%s\".", output.name(), output.handledBy()));
            }
            if (!parentUri.startsWith("/archive")) {
                parentUri = "/archive" + parentUri;
            }

            String createdUri = null;
            final String type = output.type();
            if (type.equals(RESOURCE.getName())) {
                if (log.isDebugEnabled()) {
                    final String template = prefix + "Inserting file resource.\n\tuser: {}\n\tparentUri: {}\n\tlabel: {}\n\ttoUpload: {}";
                    log.debug(template, userI.getLogin(), parentUri, label, toUpload);
                }

                try {
                    final URIManager.DataURIA uri = UriParserUtils.parseURI(parentUri);
                    if (!Permissions.canEdit(userI, ((URIManager.ArchiveItemURI) uri).getSecurityItem())) {
                        final String message = String.format(prefix + "User does not have permission to add resources to item with URI %s.", parentUri);
                        log.error(message);
                        throw new UnauthorizedException(message);
                    }

                    final XnatResourcecatalog resourcecatalog = catalogService.insertResources(userI, parentUri,
                            toUpload, uploadEventId, true, true,
                            label, null, output.format(), null);
                    createdUri = UriParserUtils.getArchiveUri(resourcecatalog);
                    if (StringUtils.isBlank(createdUri)) {
                        createdUri = parentUri + "/resources/" + resourcecatalog.getLabel();
                    }
                } catch (ClientException e) {
                    final String message = String.format(prefix + ": " + e.getMessage(), parentUri);
                    log.error(message);
                    throw new UnauthorizedException(message);
                } catch (Exception e) {
                    final String message = prefix + "Could not upload files to resource.";
                    log.error(message);
                    throw new ContainerException(message, e);
                }
                //Insert Resources does a refresh catalog action.
                //try {
                //    catalogService.refreshResourceCatalog(userI, createdUri);
                //} catch (ServerException | ClientException e) {
                //    final String message = String.format(prefix + "Could not refresh catalog for resource %s.", createdUri);
                //    log.error(message, e);
                //}
            } else if (CommandWrapperOutputEntity.Type.xmlUploadTypes().contains(type)) {

                File itemXml;
                if (toUpload.size() != 1 || !(itemXml = toUpload.get(0)).getName().matches(".*\\.xml$")) {
                    final String message = prefix + "Expecting precisely one xml file to upload for " + type +
                            "; found " + toUpload;
                    log.error(message);
                    throw new ContainerException(message);
                }

                log.debug("{}Inserting {}.\n\tuser: {}\n\tparentUri: {}\n\tlabel: {}\n\txml: {}",
                        prefix, type, userI.getLogin(), parentUri, label, itemXml);

                try {
                    // Get item from xml
                    XFTItem item = catalogService.insertXmlObject(userI, itemXml,
                            true, Collections.<String, Object>emptyMap(), uploadEventId);

                    if (item == null) {
                        throw new Exception();
                    }

                    createdUri = UriParserUtils.getArchiveUri(item);

                    if (type.equals(ASSESSOR.getName())) {
                        // The URI that is returned from UriParserUtils is technically correct, but doesn't work very well.
                        // It is of the form /experiments/{assessorId}. If we try to upload resources to it, that will fail.
                        // We have to manually turn it into a URI of the form /experiments/{sessionId}/assessors/{assessorId}.
                        final Matcher createdUriMatchesExperimentUri = experimentUri.matcher(createdUri);
                        createdUri = createdUriMatchesExperimentUri.matches() ?
                                String.format("%s/assessors/%s", parentUri, createdUriMatchesExperimentUri.group(2)) :
                                createdUri;
                    }
                } catch (IOException e) {
                    final String message = prefix + "Could not read " + itemXml;
                    log.error(message);
                    throw new ContainerException(message, e);
                } catch (Exception e) {
                    final String message = prefix + "Could not insert item from XML file " + itemXml;
                    log.error(message);
                    throw new ContainerException(message, e);
                }
            }

            log.info("{}Done uploading output \"{}\". URI of created item: {}", prefix, output.name(), createdUri);

            // We use the "fromOutputHandler" property here rather than name. The reason is that we will be looking
            // up the value later based on what users set in subsequent handers' "handled-by" properties, and the value
            // they put in that property is going to be the output handler name.
            wrapperInputAndOutputValues.put(output.fromOutputHandler(), createdUri);
            
            return output.toBuilder().created(createdUri).build();
        }

        private ContainerMount getMount(final String mountName) throws ContainerException {

            if(outputMounts == null || outputMounts.isEmpty()){
                for (final ContainerMount mountOut : toFinalize.mounts()) {
                    outputMounts.put(mountOut.name(), mountOut);
                }
            }
            ContainerMount containerMount = outputMounts.get(mountName);
            if(containerMount != null){
                return containerMount;
            }

            // Mount does not exist
            throw new ContainerException(String.format(prefix + "Mount \"%s\" does not exist.", mountName));
        }

        private String getUriByInputOrOutputHandlerName(final String name) {
            if (log.isDebugEnabled()) {
                log.debug(String.format(prefix + "Getting URI for input or output handler \"%s\".", name));
            }

            if (wrapperInputAndOutputValues.containsKey(name)) {
                final String uri = wrapperInputAndOutputValues.get(name);
                if (log.isDebugEnabled()) {
                    log.debug(prefix + String.format("Found uri value \"%s\".", uri));
                }
                return uri;
            }

            if (log.isDebugEnabled()) {
                log.debug(String.format(prefix + "No input or output handler found with name \"%s\".", name));
            }
            return null;
        }

        @Nullable
        private Container getWrapupContainer(final String parentSourceObjectName) {
            return wrapupContainerMap.get(parentSourceObjectName);
        }

        private List<File> matchGlob(final String rootPath, final String glob) {
            final File rootDir = new File(rootPath);
            final File[] files = rootDir.listFiles();
            return files == null ? Lists.<File>newArrayList() : Arrays.asList(files);
        }
    }

    private static class OutputsAndExceptions {
        List<ContainerOutput> outputs;
        List<Exception> exceptions;

        OutputsAndExceptions(final List<ContainerOutput> outputs,
                             final List<Exception> exceptions) {
            this.outputs = outputs;
            this.exceptions = exceptions;
        }
    }
}
