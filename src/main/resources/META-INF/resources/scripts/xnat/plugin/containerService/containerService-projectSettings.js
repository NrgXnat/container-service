/*
 * web: containerServices-projectSettings.js
 * XNAT http://www.xnat.org
 * Copyright (c) 2005-2017, Washington University School of Medicine and Howard Hughes Medical Institute
 * All Rights Reserved
 *
 * Released under the Simplified BSD.
 */

/*!
 * Manage Site-wide Command Configs
 */

console.log('containerServices-projectSettings.js');

var XNAT = getObject(XNAT || {});
XNAT.plugin = getObject(XNAT.plugin || {});
XNAT.plugin.containerService = getObject(XNAT.plugin.containerService || {});

(function(factory){
    if (typeof define === 'function' && define.amd) {
        define(factory);
    }
    else if (typeof exports === 'object') {
        module.exports = factory();
    }
    else {
        return factory();
    }
}(function(){

    var projCommandConfigManager,
        projConfigDefinition,
        undefined,
        rootUrl = XNAT.url.rootUrl,
        restUrl = XNAT.url.restUrl,
        csrfUrl = XNAT.url.csrfUrl,
        commandList,
        wrapperList,
        projectCommandOptions = {},
        errorHandler;

    var projectDataTypeSingularName = XNAT.app.displayNames.singular.project;

    XNAT.plugin.containerService.projCommandConfigManager = projCommandConfigManager =
        getObject(XNAT.plugin.containerService.projCommandConfigManager || {});

    XNAT.plugin.containerService.projConfigDefinition = projConfigDefinition =
        getObject(XNAT.plugin.containerService.projConfigDefinition || {});

    XNAT.plugin.containerService.commandInfo = commandInfo =
            getObject(XNAT.plugin.containerService.commandInfo || {});

    XNAT.plugin.containerService.commandList = commandList = [];
    XNAT.plugin.containerService.wrapperList = wrapperList = {};
    XNAT.plugin.containerService.containerManagerUsersContact = containerManagerUsersContact = [];

    XNAT.plugin.containerService.errorHandler = errorHandler = function(e, title){
        console.log(e);
        title = (title) ? 'Error Found: '+ title : 'Error';
        var errormsg = (e.statusText) ? '<p><strong>Error ' + e.status + ': '+ e.statusText+'</strong></p><p>' + e.responseText + '</p>' : e;
        xmodal.alert({
            title: title,
            content: errormsg,
            okAction: function () {
                xmodal.closeAll();
            }
        });
    }

    function getUrlParams(){
        var paramObj = {};

        // get the querystring param, redacting the '?', then convert to an array separating on '&'
        var urlParams = window.location.search.substr(1,window.location.search.length);
        urlParams = urlParams.split('&');

        urlParams.forEach(function(param){
            // iterate over every key=value pair, and add to the param object
            param = param.split('=');
            paramObj[param[0]] = param[1];
        });

        return paramObj;
    }

     function formatInfo(key, val='', bold=false) {
         var str = '';
         if (bold) {
           str +=  '<b>';
         }
         str += String(key).charAt(0).toUpperCase() + String(key).slice(1);
         if (bold) {
           str +=  '</b>';
         }
          if (val !== '') {
             str += ':'+val ;
             str += '<br><br>';
          }
          return str;
     }

     function showVisibilityText(item) {
           return formatInfo(item['visibility']);
     }

   commandInfo.dialog = function(command, text) {
      var commandMetadataContent = "";
      var commandMetadata = command['command-metadata'];
      for (const key in commandMetadata) {
                if (commandMetadata.hasOwnProperty(key)) {
                    commandMetadataContent += `${formatInfo(key, commandMetadata[key], true)}`;
                }
      }
      if (commandMetadataContent === "") {
         commandMetadataContent = "No metadata available for " + text
      }
      XNAT.dialog.open({
                  width: 450,
                  title: 'Information about ' + text,
                  content: commandMetadataContent,
                  buttons: [
                      {
                          label: 'OK',
                          isDefault: true,
                          close: true
                      }
                  ]
              });
   }


    XNAT.plugin.containerService.getProjectId = getProjectId = function() {
        if (XNAT.data.context.projectID.length > 0) return XNAT.data.context.projectID;
        return getUrlParams().id;
    };

    XNAT.plugin.containerService.getMailToContainerManager = getMailToContainerManager = function(command, wrapper) {
            return "Please contact the Site Container Manager to gain access to the command " +  command['name'] + " Context: " + wrapper['name'] + " for the Project " + getProjectId();
        };

    function commandUrl(appended){
        appended = isDefined(appended) ? appended : '';
        return restUrl('/xapi/commands' + appended);
    }

    function configUrl(commandId,wrapperName,appended){
        appended = isDefined(appended) ? '?' + appended : '';
        if (!commandId || !wrapperName) return false;
        return csrfUrl('/xapi/projects/'+getProjectId()+'/commands/'+commandId+'/wrappers/'+wrapperName+'/config' + appended);
    }

    function sitewideConfigEnableUrl(commandObj,wrapperObj,flag){
        var command = commandObj.id,
            wrapperName = wrapperObj.name;
        return csrfUrl('/xapi/commands/'+command+'/wrappers/'+wrapperName+'/' + flag);
    }

    function projConfigEnableUrl(commandId,wrapperName,flag){
        if (!commandId || !wrapperName || !flag) return false;
        var projectId = getProjectId();
        return csrfUrl('/xapi/projects/'+projectId+'/commands/'+commandId+'/wrappers/'+wrapperName+'/' + flag);
    }


    function refreshCommandWrapperList(wrapperId) {
        const wrapper = wrapperList[wrapperId];
        if (!projectCommandOptions[wrapperId]) {
            projectCommandOptions[wrapperId] = {
                label: wrapper.name,
                value: wrapper.name,
                'command-id': wrapper.commandId,
                'wrapper-id': wrapper.id,
                contexts: wrapper.contexts
            };
        }
        projectCommandOptions[wrapperId].enabled = wrapper.enabled;
    }

    function anyCommandsEnabled() {
        return Object.keys(projectCommandOptions).some(function(k) {
            return projectCommandOptions[k].enabled;
        });
    }

    projCommandConfigManager.getCommands = projCommandConfigManager.getAll = function(callback){

        callback = isFunction(callback) ? callback : function(){};
        return XNAT.xhr.get({
            url: commandUrl('?restrictToEnabledForSite=true'),
            dataType: 'json',
            success: function(data){
                if (data) {
                    return data;
                }
                callback.apply(this, arguments);
            }
        });
    };

    projConfigDefinition.getConfig = function(commandId,wrapperName,callback){
        if (!commandId || !wrapperName) return false;
        callback = isFunction(callback) ? callback : function(){};
        return XNAT.xhr.get({
            url: configUrl(commandId,wrapperName),
            dataType: 'json',
            success: function(data){
                if (data) {
                    return data;
                }
                callback.apply(this, arguments);
            }
        });
    };

    projCommandConfigManager.getEnabledStatus = function(command,wrapper,callback){
        callback = isFunction(callback) ? callback : function(){};
        return XNAT.xhr.get({
            url: projConfigEnableUrl(command.id,wrapper.name,'enabled'),
            success: function(data){
                if (data) {
                    return data;
                }
                callback.apply(this, arguments);
            },
            fail: function(e){
                errorHandler(e);
            }
        });
    };

    projConfigDefinition.table = function(config) {

        // initialize the table - we'll add to it below
        var pcdTable = XNAT.table({
            className: 'command-config-definition xnat-table '+config.type,
            style: {
                width: '100%',
                marginTop: '15px',
                marginBottom: '15px'
            }
        });

        function basicConfigInput(name,value) {
            value = (value === undefined || value === null || value == 'null') ? '' : value;
            // Workaround to handle quotes in value string
            var $input = $(spawn('input', {name: name, type: 'text'}));
            $input.attr('value', value);
            return $input.get(0);
        }

        function configCheckbox(name,checked,onText,offText){
            onText = onText || 'Yes';
            offText = offText || 'No';
            var enabled = !!checked;
            var ckbox = spawn('input.config-enabled', {
                type: 'checkbox',
                checked: enabled,
                value: 'true',
                data: { name: name, checked: enabled },
            });

            return spawn('div.left', [
                spawn('label.switchbox', [
                    ckbox,
                    ['span.switchbox-outer', [['span.switchbox-inner']]],
                    ['span.switchbox-on',[onText]],
                    ['span.switchbox-off',[offText]]
                ])
            ]);
        }

        function hiddenConfigInput(name,value) {
            return XNAT.ui.input.hidden({
                name: name,
                value: value
            }).element;
        }


        // determine which type of table to build.
        if (config.type === 'inputs') {
            var inputs = config.inputs;

            // add table header row
            pcdTable.tr()
                .th({ addClass: 'left', html: '<b>Input</b>' })
                .th('<b>Default Value</b>')
                .th('<b>Matcher Value</b>')
                .th('<b>User-Settable?</b>')
                .th('<b>Advanced?</b>');

            for (i in inputs) {
                var input = inputs[i];
                pcdTable.tr({ data: { input: i }, className: 'input' })
                    .td( { data: { key: 'key' }, addClass: 'left'}, i )
                    .td( { data: { key: 'property', property: 'default-value' }}, basicConfigInput('defaultVal',input['default-value']) )
                    .td( { data: { key: 'property', property: 'matcher' }}, basicConfigInput('matcher',input['matcher']) )
                    .td( { data: { key: 'property', property: 'user-settable' }}, [['div', [configCheckbox('userSettable',input['user-settable']) ]]])
                    .td( { data: { key: 'property', property: 'advanced' }}, [['div', [configCheckbox('advanced',input['advanced']) ]]]);
            }

        } else if (config.type === 'outputs') {
            var outputs = config.outputs;

            // add table header row
            pcdTable.tr()
                .th({ addClass: 'left', html: '<b>Output</b>' })
                .th({ addClass: 'left', width: '75%', html: '<b>Label</b>' });

            for (o in outputs) {
                var output = outputs[o];
                pcdTable.tr({ data: { output: o }, className: 'output' })
                    .td( { data: { key: 'key' }, addClass: 'left'}, o )
                    .td( { data: { key: 'property', property: 'label' }}, basicConfigInput('label',output['label']) );
            }

        }

        projConfigDefinition.$table = $(pcdTable.table);

        return pcdTable.table;
    };


    projConfigDefinition.dialog = function(commandId,wrapperName){
        // get command definition
        projConfigDefinition.getConfig(commandId,wrapperName)
            .success(function(data){
                var tmpl = $('div#proj-command-config-template').find('.panel');
                var tmplBody = $(tmpl).find('.panel-body').html('');

                var inputs = data.inputs;
                var outputs = data.outputs;

                tmplBody.spawn('h3','Inputs');
                tmplBody.append(projConfigDefinition.table({ type: 'inputs', inputs: inputs }));

                if (outputs.length) {
                    tmplBody.spawn('h3','Outputs');
                    tmplBody.append(projConfigDefinition.table({ type: 'outputs', outputs: outputs }));
                }

                XNAT.dialog.open({
                    title: 'Set Config Values for '+wrapperName,
                    width: 850,
                    content: spawn('div.panel'),
                    beforeShow: function(obj){
                        var $panel = obj.$modal.find('.panel');
                        $panel.append(tmpl.html());
                        $panel.find('input[type=checkbox]').each(function(){
                            $(this).prop('checked',$(this).data('checked'));
                        })
                    },
                    buttons: [
                        {
                            label: 'Save',
                            isDefault: true,
                            close: false,
                            action: function(obj){
                                var $panel = obj.$modal.find('.panel');
                                var configObj = { inputs: {}, outputs: {} };

                                // gather input items from table
                                var inputRows = $panel.find('table.inputs').find('tr.input');
                                $(inputRows).each(function(){
                                    var row = $(this);
                                    // each row contains multiple cells, each of which defines a property.
                                    var key = $(row).find("[data-key='key']").html();
                                    configObj.inputs[key] = {};

                                    $(row).find("[data-key='property']").each(function(){
                                        var propKey = $(this).data('property');
                                        var formInput = $(this).find('input');
                                        if ($(formInput).is('input[type=checkbox]')) {
                                            var checkboxVal = ($(formInput).is(':checked')) ? $(formInput).val() : 'false';
                                            configObj.inputs[key][propKey] = checkboxVal;
                                        } else {
                                            configObj.inputs[key][propKey] = $(this).find('input').val();
                                        }
                                    });

                                });

                                // gather output items from table
                                var outputRows = $panel.find('table.outputs').find('tr.output');
                                $(outputRows).each(function(){
                                    var row = $(this);
                                    // each row contains multiple cells, each of which defines a property.
                                    var key = $(row).find("[data-key='key']").html();
                                    configObj.outputs[key] = {};

                                    $(row).find("[data-key='property']").each(function(){
                                        var propKey = $(this).data('property');
                                        configObj.outputs[key][propKey] = $(this).find('input').val();
                                    });

                                });

                                // POST the updated command config
                                XNAT.xhr.postJSON({
                                    url: configUrl(commandId,wrapperName,'enabled=true'),
                                    // dataType: 'json',
                                    data: JSON.stringify(configObj),
                                    success: function() {
                                        XNAT.ui.banner.top(2000, '<b>"' + wrapperName + '"</b> updated.', 'success');
                                        XNAT.dialog.closeAll();
                                        xmodal.closeAll();
                                    },
                                    fail: function(e) {
                                        errorHandler(e, 'Could Not Update Config Definition');
                                    }
                                })
                            }
                        },
                        {
                            label: 'Reset to Site-wide Default',
                            close: false,
                            action: function(obj){

                                XNAT.xhr.delete({
                                    url: configUrl(commandId,wrapperName),
                                    success: function(){
                                        XNAT.ui.banner.top(2000, 'Config settings reset to site-wide defaults', 'success');
                                        XNAT.dialog.closeAll();
                                    },
                                    fail: function(e){
                                        errorHandler(e,'Could not reset project-based config settings for this command')
                                    }
                                })
                            }
                        },
                        {
                            label: 'Cancel',
                            close: true
                        }
                    ]

                });

            })
            .fail(function(e){
                errorHandler(e, 'Could not retrieve configuration for this command');
            });

    };

    function disabledSpan() {
        return spawn('span',{ 'style': { 'color': '#808080' }},'disabled');
    }

    projCommandConfigManager.filterCommandList = filterCommandList = function(){
            // check all active filters
            var pccmTable = $(document).find('table.project-command-configs'),
                commandFilterKey = pccmTable.find('input.filter-commandlabel').val().toLowerCase() || false,
                containerFilterKey = pccmTable.find('input.filter-container').val().toLowerCase() || false,
                visibilityFilterKey = pccmTable.find('input.filter-visibility').val().toLowerCase() || false;

            var rowsToFilter = $(pccmTable).find('tr.command-config-listing');
            if (!commandFilterKey && !containerFilterKey && !visibilityFilterKey) {
                rowsToFilter.removeClass('hidden');
            } else {
                rowsToFilter.each(function(){
                    // show this row only if filters are empty or have values matching the command or container
                    var showRow = (
                        ($(this).prop('title').toLowerCase().indexOf(commandFilterKey) >= 0 || !commandFilterKey ) &&
                        ($(this).data('image').toLowerCase().indexOf(containerFilterKey) >= 0 || !containerFilterKey ) &&
                         ($(this).data('visibility').toLowerCase().indexOf(visibilityFilterKey) >= 0 || !visibilityFilterKey ));

                    if (showRow) {
                        $(this).removeClass('hidden')
                    } else {
                        $(this).addClass('hidden')
                    }
                })
            }
        }

    projCommandConfigManager.table = function(config){

        // initialize the table - we'll add to it below
        var pccmTable = XNAT.table({
            className: 'xnat-table project-command-configs '+config.className,
            style: {
                width: '100%',
                marginTop: '15px',
                marginBottom: '15px'
            },
            id: config.id
        });

        // add table header row
        pccmTable.tr()
            .th({ addClass: 'left', html: '<b>XNAT Command Label</b>' })
            .th('<b>Container</b>')
            .th('<b>Enabled</b>')
            .th('<b>Visibility</b>')
            .th('<b>Actions</b>');

        // add master switch
        pccmTable.tr({ 'style': { 'background-color': '#f3f3f3' }})
            .td([ spawn('div',[ filterCommandLabel()]) ])
            .td([ spawn('div',[ filterContainer()]) ])
            .td([ spawn('div',[ masterCommandCheckbox() ]) ])
            .td([ spawn('div',[filterVisibility()]) ])
            .td();

        function viewLink(command, wrapper, enabledForSite, enabledForProject){
            var label = (wrapper.description.length) ?
                wrapper.description :
                wrapper.name;
            return spawn(
                'a.link|href=#!',
                {
                    onclick: function(e){
                        e.preventDefault();
                        commandInfo.dialog(command,  wrapper.name, false);
                    }
                },
                spawn('b', label)
            );
        }

        function spawnContactContainerManager(command, wrapper) {
            return spawn('button.btn.sm',
            {
                onclick: function(e) {
                          var text = XNAT.plugin.containerService.getMailToContainerManager(command, wrapper, false);
                          XNAT.dialog.open({
                                      width: 450,
                                      title: 'Access to  ' + command['name'],
                                      content: text,
                                      buttons: [
                                          {
                                              label: 'OK',
                                              isDefault: true,
                                              close: true
                                          }
                                      ]
                                  });
           }
            }, [ spawn('i.fa.fa-question-circle-o') ]);
        }

        function editConfigButton(command, wrapper, enabledForProject){
            var commandVisibility = command['visibility'];
            if (commandVisibility === "protected" && enabledForProject === false) {
                return spawnContactContainerManager(command, wrapper);
            } else {
                return spawn('button.btn.sm', {
                    onclick: function(e){
                        e.preventDefault();
                        projConfigDefinition.dialog(command.id, wrapper.name, false);
                    },
                    title: 'Set Defaults'
                }, [ spawn('i.fa.fa-pencil') ]);
            }
        }


        function enabledCheckbox(command,wrapper) {
           var isEnabled = wrapperList[wrapper.id].enabled;
            var ckbox = spawn('input.config-enabled.wrapper-enable', {
                type: 'checkbox',
                checked: isEnabled  ? 'checked' : false,
                value: 'true',
                id: 'wrapper-'+wrapper.id+'-enable',
                data: { wrappername: wrapper.name, commandid: command.id },
                onchange: function(){
                    // save the status when clicked
                    var checkbox = this;
                    var enabled = checkbox.checked;
                    var enabledFlag = (enabled) ? 'enabled' : 'disabled';

                    XNAT.xhr.put({
                        url: projConfigEnableUrl(command.id,wrapper.name,enabledFlag),
                        success: function(){
                            var status = (enabled ? ' enabled' : ' disabled');
                            checkbox.value = enabled;
                            wrapperList[wrapper.id].enabled = enabled;
                            refreshCommandWrapperList(wrapper.id);
                            projCommandOrchestrationManager.init();
                            XNAT.ui.banner.top(2000, '<b>' + wrapper.name+ '</b> ' + status, 'success');
                        }
                    });
                    projCommandConfigManager.setMasterEnableSwitch();
                }
            });

            if (!isEnabled && command['visibility'] === 'protected') {
                return spawn('div.center', [disabledSpan()]);
            } else {
                return spawn('div.center', [
                    spawn('label.switchbox|title=' + wrapper.name, [
                        ckbox,
                        ['span.switchbox-outer', [['span.switchbox-inner']]]
                    ])
                ]);
            }
        }


        function masterCommandCheckbox(){

            var ckbox = spawn('input.config-enabled', {
                type: 'checkbox',
                checked: false,
                value: 'true',
                id: 'wrapper-all-enable',
                onchange: function(){
                    // save the status when clicked
                    var checkbox = this;
                    enabled = checkbox.checked;
                    var enabledFlag = (enabled) ? 'enabled' : 'disabled';
                    var atleastOneRowHasACheckBox = false;
                    // iterate through each command toggle and set it to 'enabled' or 'disabled' depending on the user's click
                    $('.wrapper-enable').each(function(){
                        var status = ($(this).is(':checked')) ? 'enabled' : 'disabled';
                        if (status !== enabledFlag) $(this).click();
                    });
                    if (atleastOneRowHasACheckBox) {
                        XNAT.ui.banner.top(2000, 'All commands <b>'+enabledFlag+'</b>.', 'success');
                    }
                }
            });

            return spawn('div.center', [
                spawn('label.switchbox|title=enable-all', [
                    ckbox,
                    ['span.switchbox-outer', [['span.switchbox-inner']]]
                ])
            ]);
        }

        function filterCommandLabel(){
            return spawn('input', {
                addClass: 'filter-data filter-commandlabel',
                type: 'text',
                title: 'Filter on the command label',
                placeholder: 'Filter by Command',
                style: { width: 'calc(100% - 12px)'},
                onkeyup: XNAT.plugin.containerService.projCommandConfigManager.filterCommandList
            })
        }

        function filterContainer(){
            return spawn('input', {
                addClass: 'filter-data filter-container',
                type: 'text',
                title: 'Filter on the container name',
                placeholder: 'Filter by Container',
                style: { width: 'calc(100% - 12px)'},
                onkeyup: XNAT.plugin.containerService.projCommandConfigManager.filterCommandList
            })
        }

         function filterVisibility(){
                return spawn('input', {
                    addClass: 'filter-data filter-visibility',
                    type: 'text',
                    title: 'Filter on the command visibility',
                    placeholder: 'Filter by Visibility',
                    style: { width: 'calc(100% - 12px)'},
                    onkeyup: XNAT.plugin.containerService.projCommandConfigManager.filterCommandList
                    })
            }


        function handleCommand(command, wrapper) {
            projCommandConfigManager.getEnabledStatus(command,wrapper).done(function(data){
                var enabled = data['enabled-for-project'];
                if (data['enabled-for-site']) {
                        wrapperList[wrapper.id] = {
                            id: wrapper.id,
                            description: wrapper.description,
                            contexts: wrapper.contexts,
                            commandId: command.id,
                            name: wrapper.name,
                            visibility: command.visibility
                        };
                        wrapperList[wrapper.id].enabled = enabled;
                        refreshCommandWrapperList(wrapper.id);

                        var label = (wrapper.description) ? wrapper.description : wrapper.name;
                        pccmTable.tr({addClass: 'command-config-listing', title: label, data: {id: wrapper.id, name: wrapper.name, image: command.image, visibility: command.visibility}})
                            .td([viewLink(command, wrapper)]).addClass('name')
                            .td(command.image)
                            .td([['div.center', [enabledCheckbox(command,wrapper)]]])
                            .td([ spawn('span.center', showVisibilityText(command)) ])
                            .td([['div.center', [editConfigButton(command,wrapper, enabled)]]]);

                }
                projCommandConfigManager.setMasterEnableSwitch();
            });
         }

   function showNoCommandExists() {
        // create a handler when no command data is returned.
         pccmTable.tr({title: 'No command config data found'})
             .td({colSpan: '5', html: 'No XNAT-enabled Commands found for this ' + projectDataTypeSingularName.toLowerCase()});
   }

  projCommandConfigManager.getAll().done(function(data) {
            commandList = data;

            if (commandList.length) {
                commandList.forEach(function(command){
                    if (command.xnat) { // if an xnat wrapper has been defined for this command...
                        for (var k = 0, l = command.xnat.length; k < l; k++) {
                            handleCommand(command, command.xnat[k]);
                        }
                    }
                });
            } else {
                showNoCommandExists();
            }

            // once command list is known, initialize automation and orchestration panels
            // initialize automation table
            XNAT.xhr.getJSON({
                url: restUrl('/xapi/users/' + PAGE.username + '/roles'),
                success: function (userRoles) {
                    XNAT.plugin.containerService.isAdmin = (userRoles.indexOf('Administrator') >= 0);
                    XNAT.plugin.containerService.commandAutomation.init();
                    projCommandOrchestrationManager.init();
                },
                fail: function (e) {
                    errorHandler(e);
                }
            });
            // then initialize the history table
            XNAT.plugin.containerService.historyTable.init(getProjectId());
        });
        if (pccmTable.table.rows.length > 2) {
            projCommandConfigManager.setMasterEnableSwitch();
        }
        projCommandConfigManager.$table = $(pccmTable.table);

        return pccmTable.table;
    };
    // examine all command toggles and set master switch to "ON" if all are checked
    projCommandConfigManager.setMasterEnableSwitch = function(){
        var allEnabled = true;
        var atleastOneRowHasACheckBox = false;
        $('.wrapper-enable').each(function() {
            atleastOneRowHasACheckBox = true;
            if (!$(this).is(':checked')) {
                allEnabled = false;
                return false;
            }
        });
        if (atleastOneRowHasACheckBox) {
            if (allEnabled) {
                $('#wrapper-all-enable').prop('checked','checked');
            } else {
                $('#wrapper-all-enable').prop('checked',false);
            }
        } else {
            $('#wrapper-all-enable').prop('disabled','disabled').parents('.switchbox').addClass('disabled').hide()
        }
    };

    projCommandConfigManager.refresh = projCommandConfigManager.refreshTable = function(container){
        var $manager = $$(container||'div#proj-command-config-list-container');

        $manager.html('');
        $manager.append(projCommandConfigManager.table({id: 'project-commands', className: '' }));
        projCommandConfigManager.setMasterEnableSwitch();
    };

    projCommandConfigManager.init = function(container){
        var $manager = $$(container||'div#proj-command-config-list-container');
        projCommandConfigManager.container = $manager;
        $manager.append(projCommandConfigManager.table({id: 'project-commands', className: '' }));
    };

    projCommandConfigManager.init();

    // Orchestration
    console.log('commandOrchestration.js');

    let projCommandOrchestrationManager;

    XNAT.plugin.containerService.projCommandOrchestrationManager = projCommandOrchestrationManager =
        getObject(XNAT.plugin.containerService.projCommandOrchestrationManager || {});

    projCommandOrchestrationManager.table = function() {
        const projectId = getProjectId();

        // initialize the table - we'll add to it below
        const coTable = XNAT.table({
            className: 'xnat-table compact',
            style: {
                width: '100%',
                marginTop: '15px',
                marginBottom: '15px'
            }
        });

        // add table header row
        coTable.tr()
            .th('<b>Name</b>')
            .th('<b>Commands</b>')
            .th({ width: 91, html: '<b>Selected</b>' });

        function radioBtn(checked, o) {
            if (o && !o.enabled) {
                return spawn('div.center', [disabledSpan()]);
            }

            const ckbox = spawn('input.orchestration-select', {
                type: 'radio',
                name: 'orchestration-select',
                checked: checked,
                value: 'true',
                onchange: function () {
                    let radio = $(this);
                    if (o) {
                        XNAT.xhr.put({
                            url: rootUrl('/xapi/orchestration/project/' + projectId + '?orchestrationId=' + o.id),
                            success: function () {
                                XNAT.ui.banner.top(1000, 'Orchestration <b>' + o.name + '</b> added', 'success');
                                projCommandConfigManager.refreshTable();
                            },
                            fail: function (e) {
                                errorHandler(e);
                                radio.prop('checked', false);
                            }
                        });
                    } else {
                        XNAT.xhr.delete({
                            url: rootUrl('/xapi/orchestration/project/' + projectId),
                            success: function () {
                                XNAT.ui.banner.top(1000, 'Orchestration removed', 'success');
                            },
                            fail: function (e) {
                                errorHandler(e);
                                radio.prop('checked', false);
                            }
                        });
                    }
                }
            });

            return spawn('div.center', [ckbox]);
        }

        XNAT.xhr.getJSON({
            url: rootUrl('/xapi/orchestration/project/' + projectId),
            success: function(data) {
                // add option for none
                coTable.tr({ 'style': { 'background-color': '#f3f3f3' }})
                    .td({className: 'name', html: 'No orchestration', colSpan: 2})
                    .td([ spawn('div',[radioBtn(!data.selectedOrchestrationId, null)]) ])

                data.availableOrchestrations.forEach(function(o) {
                    coTable.tr()
                        .td(o.name)
                        .td($.map(o.wrapperIds, function(wid) {
                            return projectCommandOptions[wid].label;
                        }).join(', '))
                        .td([ spawn('div',[radioBtn(data.selectedOrchestrationId === o.id, o)]) ])
                })
            },
            error: function(xhr) {
                errorHandler(xhr);
            }
        });

        return coTable.table;
    };

    projCommandOrchestrationManager.init = function() {
        const $manager = $('div#command-orchestration');
        $manager.empty();
        $manager.append(projCommandOrchestrationManager.table());
    };
}));