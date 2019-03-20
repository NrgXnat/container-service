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
        wrapperList;

    XNAT.plugin.containerService.projCommandConfigManager = projCommandConfigManager =
        getObject(XNAT.plugin.containerService.projCommandConfigManager || {});

    XNAT.plugin.containerService.projConfigDefinition = projConfigDefinition =
        getObject(XNAT.plugin.containerService.projConfigDefinition || {});

    XNAT.plugin.containerService.commandList = commandList = [];
    XNAT.plugin.containerService.wrapperList = wrapperList = {};


    function spacer(width){
        return spawn('i.spacer', {
            style: {
                display: 'inline-block',
                width: width + 'px'
            }
        });
    }

    function errorHandler(e, title){
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

    function getProjectId(){
        if (XNAT.data.context.projectID.length > 0) return XNAT.data.context.projectID;
        return getUrlParams().id;
    }

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

    projCommandConfigManager.getCommands = projCommandConfigManager.getAll = function(callback){

        callback = isFunction(callback) ? callback : function(){};
        return XNAT.xhr.get({
            url: commandUrl(),
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
            return '<input type="text" name="'+name+'" value="'+value+'" />';
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
                                    dataType: 'json',
                                    data: JSON.stringify(configObj),
                                    success: function() {
                                        XNAT.ui.banner.top(2000, '<b>"' + wrapperName + '"</b> updated.', 'success');
                                        XNAT.dialog.closeAll();
                                        xmodal.closeAll();
                                    },
                                    fail: function(e) {
                                        xmodal.alert({
                                            title: 'Error',
                                            content: '<p><strong>Error '+e.status+'</strong></p><p>'+e.statusText+'</p>',
                                            okAction: function(){
                                                XNAT.dialog.closeAll();
                                                xmodal.closeAll();
                                            }
                                        });
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

    projCommandConfigManager.table = function(config){

        // initialize the table - we'll add to it below
        var pccmTable = XNAT.table({
            className: 'xnat-table '+config.className,
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
            .th('<b>Actions</b>');

        // add master switch
        pccmTable.tr({ 'style': { 'background-color': '#f3f3f3' }})
            .td( {className: 'name', colSpan: 2 }, 'Enable / Disable All Commands' )
            .td([ spawn('div',[ masterCommandCheckbox() ]) ])
            .td();

        function viewLink(command, wrapper){
            var label = (wrapper.description.length) ?
                wrapper.description :
                wrapper.name;
            return spawn(
                'a.link|href=#!',
                {
                    onclick: function(e){
                        e.preventDefault();
                        projConfigDefinition.dialog(command.id, wrapper.name, false);
                    }
                },
                spawn('b', label)
            );
        }

        function editConfigButton(command,wrapper){
            return spawn('button.btn.sm', {
                onclick: function(e){
                    e.preventDefault();
                    projConfigDefinition.dialog(command.id, wrapper.name, false);
                }
            }, 'Set Defaults');
        }

        function enabledCheckbox(command,wrapper){
            projCommandConfigManager.getEnabledStatus(command,wrapper).done(function(data){
                var enabled = data['enabled-for-site'] && data['enabled-for-project'];
                $('#wrapper-'+wrapper.id+'-enable').prop('checked',enabled);
                wrapperList[wrapper.id].enabled = enabled;

                if (data['enabled-for-site'] === false) {
                    // if a command has been disabled at the site-wide level, don't allow user to toggle it.
                    // disable the input, and add a 'disabled' class to the input controller
                    // or, remove the input and controller entirely.  
                    $('#wrapper-'+wrapper.id+'-enable').prop('disabled','disabled')
                        .parents('.switchbox').addClass('disabled').hide()
                        .parent('div').html(spawn('span',{ 'style': { 'color': '#808080' }},'disabled'));
                }
                projCommandConfigManager.setMasterEnableSwitch();
            });

            var ckbox = spawn('input.config-enabled.wrapper-enable', {
                type: 'checkbox',
                checked: false,
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
                            XNAT.ui.banner.top(2000, '<b>' + wrapper.name+ '</b> ' + status, 'success');
                        }
                    });

                    projCommandConfigManager.setMasterEnableSwitch();
                }
            });

            return spawn('div.center', [
                spawn('label.switchbox|title=' + wrapper.name, [
                    ckbox,
                    ['span.switchbox-outer', [['span.switchbox-inner']]]
                ])
            ]);
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

                    // iterate through each command toggle and set it to 'enabled' or 'disabled' depending on the user's click
                    $('.wrapper-enable').each(function(){
                        var status = ($(this).is(':checked')) ? 'enabled' : 'disabled';
                        if (status !== enabledFlag) $(this).click();
                    });
                    XNAT.ui.banner.top(2000, 'All commands <b>'+enabledFlag+'</b>.', 'success');
                }
            });

            return spawn('div.center', [
                spawn('label.switchbox|title=enable-all', [
                    ckbox,
                    ['span.switchbox-outer', [['span.switchbox-inner']]]
                ])
            ]);
        }

        projCommandConfigManager.getAll().done(function(data) {
            commandList = data;

            if (commandList) {
                commandList.forEach(function(command){
                    if (command.xnat) { // if an xnat wrapper has been defined for this command...
                        for (var k = 0, l = command.xnat.length; k < l; k++) {
                            var wrapper = command.xnat[k];
                            wrapperList[wrapper.id] = {
                                id: wrapper.id,
                                description: wrapper.description,
                                contexts: wrapper.contexts
                            };

                            pccmTable.tr({title: wrapper.name, data: {id: wrapper.id, name: wrapper.name, image: command.image}})
                                .td([viewLink(command, wrapper)]).addClass('name')
                                .td(command.image)
                                .td([['div.center', [enabledCheckbox(command,wrapper)]]])
                                .td([['div.center', [editConfigButton(command,wrapper)]]]);
                        }
                    }
                });

            } else {
                // create a handler when no command data is returned.
                pccmTable.tr({title: 'No command config data found'})
                    .td({colSpan: '5', html: 'No XNAT-enabled Commands Found'});
            }

            // once command list is known, initialize automation panel
            commandAutomation.init();

            // then initialize the history table
            XNAT.plugin.containerService.historyTable.init(getProjectId());
        });

        projCommandConfigManager.$table = $(pccmTable.table);

        return pccmTable.table;
    };

    // examine all command toggles and set master switch to "ON" if all are checked
    projCommandConfigManager.setMasterEnableSwitch = function(){
        var allEnabled = true;
        $('.wrapper-enable').each(function(){
            if (!$(this).is(':checked')) {
                allEnabled = false;
                return false;
            }
        });

        if (allEnabled) {
            $('#wrapper-all-enable').prop('checked','checked');
        } else {
            $('#wrapper-all-enable').prop('checked',false);
        }
    };

    projCommandConfigManager.importSiteWideEnabledStatus = function(){
        $('.wrapper-enable').each(function(){
            // check current status and site-wide setting, then reconcile differences.
            // For now, do not disable a command in the project if it is not enabled in the site

            var $toggle = $(this),
                projStatus = ($toggle.is(':checked')) ? 'enabled' : 'disabled';
            XNAT.xhr.getJSON({
                url: sitewideConfigEnableUrl({ id: $toggle.data('commandid')} , { name: $toggle.data('wrappername') }, 'enabled'),
                success: function(status){
                    if (projStatus === 'disabled' && status) {
                        $toggle.click();
                        projCommandConfigManager.setMasterEnableSwitch();
                    }
                },
                fail: function(e){
                    errorHandler(e, 'Could not import site-wide setting for '+$(this).data('wrappername'));
                }
            })
        });
    };

    projCommandConfigManager.init = function(container){
        var $manager = $$(container||'div#proj-command-config-list-container');

        projCommandConfigManager.container = $manager;

        $manager.append(projCommandConfigManager.table({id: 'project-commands', className: '' }));

    };

    // delay initializing this panel until automation panel is defined.

    /* ================== *
     * Command Automation *
     * ================== */

    console.log('commandAutomation.js');

    var commandAutomation;

    XNAT.plugin.containerService.commandAutomation = commandAutomation =
        getObject(XNAT.plugin.containerService.commandAutomation || {});

    function getCommandAutomationUrl(appended){
        appended = (appended) ? '?'+appended : '';
        return restUrl('/xapi/commandeventmapping' + appended);
    }
    function postCommandAutomationUrl(flag){
        flag = (flag) ? '/'+flag : ''; // can be used to set 'enabled' or 'disabled' flag
        return csrfUrl('/xapi/commandeventmapping' + flag);
    }
    function commandAutomationIdUrl(id){
        return csrfUrl('/xapi/commandeventmapping/' + id );
    }

    commandAutomation.deleteAutomation = function(id){
        if (!id) return false;
        XNAT.xhr.delete({
            url: commandAutomationIdUrl(id),
            success: function(){

                XNAT.ui.dialog.open({
                    title: 'Success',
                    width: 400,
                    content: 'Successfully deleted command event mapping.',
                    buttons: [
                        {
                            label: 'OK',
                            isDefault: true,
                            close: true,
                            action: function(){
                                XNAT.plugin.containerService.commandAutomation.refresh();
                            }
                        }
                    ]
                })
            },
            fail: function(e){
                errorHandler(e);
            }
        })
    };

    $(document).on('change','#automationEventSelector',function(){
        var eventContexts = $(this).find('option:selected').data('contexts');
        eventContexts = eventContexts.split(' ');

        // disable any command that doesn't match the available contexts for this event
        $(document).find('#automationCommandSelector')
            .prop('selectedIndex',-1)
            .find('option').each(function(){
            var $option = $(this);
            $option.prop('disabled','disabled');

            var commandContexts = $option.data('contexts') || '';
            commandContexts = commandContexts.split(' ');

            eventContexts.forEach(function(eventContext){
                if (commandContexts.indexOf(eventContext) >= 0) {
                    $option.prop('disabled',false)
                }
            });
        });
    });

    $(document).on('change','#automationCommandSelector',function(){
        var commandId = $(this).find('option:selected').data('command-id');
        $('#event-command-identifier').val(commandId);
    });

    $(document).on('click','.deleteAutomationButton',function(){
        var automationID = $(this).data('id');
        if (automationID) {
            XNAT.xhr.delete({
                url: commandAutomationIdUrl(automationID),
                success: function(){
                    XNAT.ui.banner.top(2000,'Successfully removed command automation from project.','success');
                    XNAT.plugin.containerService.commandAutomation.refresh();
                },
                fail: function(e){
                    errorHandler(e, 'Could not delete command automation');
                }
            })
        }
    });

    commandAutomation.addDialog = function(){
        // get all commands and wrappers that are known to this project, then open a dialog to allow user to configure an automation.
        var projectId = getProjectId();

        function eventSelector(options, description){
            // receive an array of objects as our list of event options
            if (options.length > 0) {
                description = (description) ? description : '';

                // build formatted options list to stick into the generated select menu
                var formattedOptions = [
                    spawn('option',{ selected: true })
                ];

                options.forEach(function(option){
                    if (isArray(option.context)) option.context = option.context.join(' ');

                    formattedOptions.push(
                        spawn('option',{
                            value: option.eventId,
                            data: { contexts: option.context },
                            html: option.label
                        } ));
                });

                var select = spawn('div.panel-element',[
                    spawn('label.element-label','On Event'),
                    spawn('div.element-wrapper',[
                        spawn('label',[
                            spawn ('select', {
                                name: 'event-type',
                                id: 'automationEventSelector'
                            }, formattedOptions )
                        ]),
                        spawn('div.description',description)
                    ]),
                    spawn('div.clear')
                ]);

                return select;
            }
        }

        function eventCommandSelector(options, description){
            // receive an array of objects as our list of options
            if (options.length > 0) {
                description = (description) ? description : 'This input is limited by the XNAT contexts available to the selected event';

                // build formatted options list to stick into the generated select menu
                var formattedOptions = [
                    spawn('option',{ selected: true })
                ];
                options.forEach(function(option){
                    var contexts = option.contexts.join(' ');

                    formattedOptions.push(
                        spawn('option',{
                            value: option.value,
                            data: { commandId: option['command-id'], contexts: contexts },
                            html: option.label
                        } ));
                });

                var select = spawn('div.panel-element',[
                    spawn('label.element-label','Run Command'),
                    spawn('div.element-wrapper',[
                        spawn('label',[
                            spawn ('select', {
                                name: 'xnat-command-wrapper',
                                id: 'automationCommandSelector'
                            }, formattedOptions )
                        ]),
                        spawn('div.description',description)
                    ]),
                    spawn('div.clear')
                ]);

                return select;
            }
        }

        if (commandList.length && Object.keys(wrapperList).length){
            var projectCommandOptions = [];
            var eventOptions = [
                {
                    eventId: 'SessionArchived',
                    context: 'xnat:imageSessionData',
                    label: 'On Session Archive'
                },
                {
                    eventId: 'ScanArchived',
                    context: 'xnat:imageScanData',
                    label: 'On Scan Archive'
                }
            ];

            commandList.forEach(function(command){
                command.xnat.forEach(function(wrapper){
                    // crosscheck each wrapper definition with our internal list of which wrappers have been enabled in the project
                    if (wrapperList[wrapper.id].enabled) {
                        projectCommandOptions.push({
                            label: wrapper.name,
                            value: wrapper.name,
                            'command-id': command.id,
                            contexts: wrapper.contexts
                        });
                    }
                });
            });

            if (Object.keys(projectCommandOptions).length > 0) {
                XNAT.ui.dialog.open({
                    title: 'Create Command Automation',
                    width: 500,
                    content: '<div class="panel pad20"></div>',
                    beforeShow: function(obj){
                        // populate form elements
                        var panel = obj.$modal.find('.panel');
                        panel.append( spawn('p','Please enter values for each field.') );
                        panel.append( eventSelector(eventOptions) );
                        panel.append( eventCommandSelector(projectCommandOptions) );
                        panel.append( XNAT.ui.panel.input.hidden({
                            name: 'project',
                            value: projectId
                        }));
                        panel.append( XNAT.ui.panel.input.hidden({
                            name: 'command-id',
                            id: 'event-command-identifier'
                        })); // this will remain without a value until a command wrapper has been selected
                    },
                    buttons: [
                        {
                            label: 'Create Automation',
                            isDefault: true,
                            close: false,
                            action: function(obj){
                                // collect input values, validate them, and post them to the command-event-mapping URI
                                var panel = obj.$modal.find('.panel'),
                                    project = panel.find('input[name=project]').val(),
                                    command = panel.find('input[name=command-id]').val(),
                                    wrapper = panel.find('select[name=xnat-command-wrapper]').find('option:selected').val(),
                                    event = panel.find('select[name=event-type]').find('option:selected').val();

                                if (project && command && wrapper && event){
                                    var data = {
                                        'project': project,
                                        'command-id': command,
                                        'xnat-command-wrapper': wrapper,
                                        'event-type': event
                                    };
                                    XNAT.xhr.postJSON({
                                        url: csrfUrl('/xapi/commandeventmapping'),
                                        data: JSON.stringify(data),
                                        success: function(){
                                            XNAT.ui.banner.top(2000, '<b>Success!</b> Command automation has been added', 'success');
                                            XNAT.ui.dialog.closeAll();
                                            XNAT.plugin.containerService.commandAutomation.refresh();
                                        },
                                        fail: function(e){
                                            errorHandler(e,'Could not create command automation');
                                        }
                                    });
                                } else {
                                    xmodal.alert('Please enter a value for each field');
                                }
                            }
                        },
                        {
                            label: 'Cancel',
                            isDefault: false,
                            close: true
                        }
                    ]
                });
            } else {
                // if no wrappers are identified, fail to launch
                errorHandler('No commands are enabled in this project. Cannot create automation','Cannot create automation');
            }
        } else {
            errorHandler('No commands are enabled in this project. Cannot create automation','Cannot create automation');
        }
    };

    commandAutomation.table = function(isAdmin){
        // if the user has admin privileges, then display additional controls.
        isAdmin = isAdmin || false;

        // initialize the table - we'll add to it below
        var caTable = XNAT.table({
            className: 'xnat-table compact',
            style: {
                width: '100%',
                marginTop: '15px',
                marginBottom: '15px'
            }
        });

        // add table header row
        caTable.tr()
            .th({ addClass: 'left', html: '<b>ID</b>' })
            .th('<b>Event</b>')
            .th('<b>Command</b>')
            .th('<b>Created By</b>')
            .th('<b>Date Created</b>')
            .th('<b>Enabled</b>')
            .th('<b>Action</b>');

        function displayDate(timestamp){
            var d = new Date(timestamp);
            return d.toISOString().replace('T',' ').replace('Z',' ');
        }

        function deleteAutomationButton(id,isAdmin){
            if (isAdmin) return spawn('button.deleteAutomationButton', {
                data: {id: id},
                title: 'Delete Automation'
            }, [ spawn ('i.fa.fa-trash') ]);
        }

        XNAT.xhr.getJSON({
            url: getCommandAutomationUrl(),
            fail: function(e){
                errorHandler(e);
            },
            success: function(data){
                // data returns an array of known command event mappings
                if (data.length){
                    var projectAutomations = false;
                    data.forEach(function(mapping){
                        if (mapping['project'] === getProjectId()) {
                            projectAutomations = true;
                            caTable.tr()
                                .td( '<b>'+mapping['id']+'</b>' )
                                .td( mapping['event-type'] )
                                .td( mapping['xnat-command-wrapper'] )
                                .td( mapping['subscription-user-name'] )
                                .td( displayDate(mapping['timestamp']) )
                                .td( mapping['enabled'] )
                                .td([ deleteAutomationButton(mapping['id'],isAdmin) ])
                        }
                    });

                    if (!projectAutomations) {
                        caTable.tr()
                            .td({ colSpan: '7', html: 'No command automations exist for this project.' });
                    }
                } else {
                    caTable.tr()
                        .td({ colSpan: '7', html: 'No command event mappings exist for this project.' });
                }
            }
        });

        commandAutomation.$table = $(caTable.table);

        return caTable.table;
    };

    commandAutomation.init = commandAutomation.refresh = function(){
        // initialize the list of command automations
        var manager = $('#command-automation-list');
        var $footer = manager.parents('.panel').find('.panel-footer');
        var isAdmin; // check current user's admin status by checking the JSP page variable PAGE.username

        manager.html('');
        $footer.html('');

        if (commandList.length && Object.keys(XNAT.plugin.containerService.wrapperList).length){
            // initialize automation table
            XNAT.xhr.getJSON({
                url: restUrl('/xapi/users/' + PAGE.username + '/roles'),
                success: function (userRoles) {
                    isAdmin = (userRoles.indexOf('Administrator') >= 0);

                    manager.append(commandAutomation.table(isAdmin));

                    if (isAdmin) {
                        var newAutomation = spawn('button.new-command-automation.btn.btn-sm.submit', {
                            html: 'Add New Command Automation',
                            onclick: function(){
                                commandAutomation.addDialog();
                            }
                        });

                        // add the 'add new' button to the panel footer
                        $footer.append(spawn('div.pull-right', [
                            newAutomation
                        ]));
                        $footer.append(spawn('div.clear.clearFix'));
                    }
                },
                fail: function (e) {
                    errorHandler(e);
                }
            });

        } else {
            // if no commands are defined, do not initialize table
            manager.append(
                spawn('p',{'style': { 'margin-top': '1em' }}, 'Cannot initialize command automations. No commands are enabled in this project.')
            )
        }

    };

    projCommandConfigManager.init();

}));