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
            
            // initialize the history table
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

    projCommandConfigManager.init();

    // command automation code removed

}));