package org.nrg.containers.jms.rest;

import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import io.swagger.annotations.ApiParam;
import io.swagger.annotations.ApiResponse;
import io.swagger.annotations.ApiResponses;
import lombok.extern.slf4j.Slf4j;
import org.nrg.action.ClientException;
import org.nrg.action.ServerException;
import org.nrg.containers.jms.preferences.QueuePrefsBean;
import org.nrg.containers.security.ContainerManagerUserAuthorization;
import org.nrg.framework.annotations.XapiRestController;
import org.nrg.prefs.exceptions.InvalidPreferenceName;
import org.nrg.xapi.rest.AbstractXapiRestController;
import org.nrg.xapi.rest.AuthDelegate;
import org.nrg.xapi.rest.XapiRequestMapping;
import org.nrg.xdat.security.services.RoleHolder;
import org.nrg.xdat.security.services.UserManagementServiceI;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.ResponseBody;

import java.util.Map;

import static org.nrg.xdat.security.helpers.AccessLevel.Authorizer;

@XapiRestController
@RequestMapping(value = "/jms_queues")
@Api("JMS Queue Settings API")
@Slf4j
public class QueueSettingsRestApi extends AbstractXapiRestController {
    private QueuePrefsBean queuePrefsBean;

    @Autowired
    public QueueSettingsRestApi(QueuePrefsBean queuePrefsBean,
                                final UserManagementServiceI userManagementService,
                                final RoleHolder roleHolder) {
        super(userManagementService, roleHolder);
        this.queuePrefsBean = queuePrefsBean;
    }

    @AuthDelegate(ContainerManagerUserAuthorization.class)
    @ApiOperation(value = "Returns a map of queue settings.", response = Map.class, responseContainer = "Map")
    @ApiResponses({@ApiResponse(code = 200, message = "Queue settings successfully retrieved."),
            @ApiResponse(code = 401, message = "Must be authenticated to access the XNAT REST API."),
            @ApiResponse(code = 500, message = "Unexpected error")})
    @XapiRequestMapping(produces = {MediaType.APPLICATION_JSON_VALUE}, method = RequestMethod.GET, restrictTo = Authorizer)
    @ResponseBody
    public ResponseEntity<Map<String, Object>> getQueueSettings() {
        return new ResponseEntity<>((Map<String, Object>) queuePrefsBean, HttpStatus.OK);
    }

    @AuthDelegate(ContainerManagerUserAuthorization.class)
    @ApiOperation(value = "Sets a map of queue settings.")
    @ApiResponses({@ApiResponse(code = 200, message = "Queue settings successfully set."),
            @ApiResponse(code = 401, message = "Must be authenticated to access the XNAT REST API."),
            @ApiResponse(code = 400, message = "Invalid input."),
            @ApiResponse(code = 500, message = "Unexpected error")})
    @XapiRequestMapping(consumes = {MediaType.APPLICATION_FORM_URLENCODED_VALUE, MediaType.APPLICATION_JSON_VALUE},
            method = RequestMethod.POST, restrictTo = Authorizer)
    @ResponseBody
    public ResponseEntity<Void> setQueueSettings(@ApiParam(value = "The map of queue settings" +
            " properties to be set.", required = true) @RequestBody final Map<String, Integer> properties)
            throws ClientException, ServerException {
        try {
            queuePrefsBean.setPreferences(properties);
        } catch (InvalidPreferenceName e) {
            throw new ClientException(e.getMessage());
        } catch (Exception e) {
            throw new ServerException(e.getMessage());
        }
        return new ResponseEntity<>(HttpStatus.OK);
    }

}

