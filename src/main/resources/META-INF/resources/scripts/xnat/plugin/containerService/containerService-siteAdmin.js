/*
 * web: containerServices-siteAdmin.js
 * XNAT http://www.xnat.org
 * Copyright (c) 2005-2017, Washington University School of Medicine and Howard Hughes Medical Institute
 * All Rights Reserved
 *
 * Released under the Simplified BSD.
 */

/*!
 * Site-wide Admin UI functions for Container Services
 */

console.log('containerServices-siteAdmin.js');

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

    /* ================ *
     * GLOBAL FUNCTIONS *
     * ================ */

    var undefined,
        rootUrl = XNAT.url.rootUrl,
        restUrl = XNAT.url.restUrl,
        csrfUrl = XNAT.url.csrfUrl;

    function spacer(width){
        return spawn('i.spacer', {
            style: {
                display: 'inline-block',
                width: width + 'px'
            }
        })
    }

    function errorHandler(e, title, closeAll){
        console.log(e);
        title = (title) ? 'Error Found: '+ title : 'Error';
        closeAll = (closeAll === undefined) ? true : closeAll;
        var errormsg = (e.statusText) ? '<p><strong>Error ' + e.status + ': '+ e.statusText+'</strong></p><p>' + e.responseText + '</p>' : e;
        XNAT.dialog.open({
            width: 450,
            title: title,
            content: errormsg,
            buttons: [
                {
                    label: 'OK',
                    isDefault: true,
                    close: true,
                    action: function(){
                        if (closeAll) {
                            xmodal.closeAll();

                        }
                    }
                }
            ]
        });
    }

    function csValidator(inputs){
        var errorMsg = [];

        if (inputs.length){
            inputs.forEach(function($input){
                if (!$input.val()){
                    errorMsg.push('<b>' + $input.prop('name') + '</b> requires a value.');
                    $input.addClass('invalid');
                }
            });

            if (errorMsg.length) {
                return errorMsg;
            } else return false;
        } else return false;
    }

    function csMultiFieldValidator(fields){
        var errorMsg = [];

        // both fields must be populated or must be empty in order to pass validation
        // field values do not have to match
        if (isArray(fields) && fields.length > 1) {
            var control = fields[0];
            var requiredEntry = ($(control).val().length > 0), passValidation = true, fieldNames = [];
            fields.forEach(function(field){
                if (requiredEntry !== ($(field).val().length > 0)) passValidation = false;
                fieldNames.push( $(field).prop('name') );
            });

            if (!passValidation) {
                $(fields).addClass('invalid');
                errorMsg.push('The following fields must all be populated, or all be empty: <b>' + fieldNames.join('</b>, <b>') + '</b>.');
            } else return false;
        }
        else {
            errorMsg.push('Validation error: Not enough inputs to compare');
        }
        return errorMsg;
    }

    function displayErrors(errorMsg) {
        var errors = [];
        errorMsg.forEach(function(msg){ errors.push(spawn('li',msg)) });

        return spawn('div',[
            spawn('p', 'Errors found:'),
            spawn('ul', errors)
        ]);
    }


    /* ====================== *
     * Container Host Manager *
     * ====================== */

    console.log('containerHostManager.js');

    var containerHostManager;

    XNAT.plugin.containerService.containerHostManager = containerHostManager =
        getObject(XNAT.plugin.containerService.containerHostManager || {});

    function containerHostUrl(appended){
        appended = isDefined(appended) ? '/' + appended : '';
        return restUrl('/xapi/docker/server' + appended);
    }

    // get the list of hosts
    containerHostManager.getHosts = containerHostManager.getAll = function(callback){
        callback = isFunction(callback) ? callback : function(){};
        return XNAT.xhr.get({
            url: containerHostUrl(),
            dataType: 'json',
            success: function(data){
                containerHostManager.hosts = data;
                callback.apply(this, arguments);
            }
        });
    };

    // dialog to create/edit hosts
    containerHostManager.dialog = function(item, isNew){
        var doWhat = (isNew) ? 'Create' : 'Edit';
        item = item || {};
        XNAT.dialog.open({
            title: doWhat + ' Container Server Host',
            content: spawn('form'),
            width: 450,
            beforeShow: function(obj){
                var $formContainer = obj.$modal.find('.xnat-dialog-content');
                $formContainer.addClass('panel');
                obj.$modal.find('form').append(
                    spawn('!', [
                        XNAT.ui.panel.input.text({
                            name: 'name',
                            label: 'Host Name'
                        }).element,
                        XNAT.ui.panel.input.text({
                            name: 'host',
                            label: 'URL'
                        }).element,
                        XNAT.ui.panel.input.text({
                            name: 'cert-path',
                            label: 'Certificate Path'
                        }).element,
                        XNAT.ui.panel.input.switchbox({
                            name: 'swarm-mode',
                            label: 'Swarm Mode',
                            onText: 'ON',
                            offText: 'OFF',
                            value: 'true'
                        }),
                        spawn('p.divider', '<strong>Path Translation (Optional)</strong><br> Use these settings to resolve differences between your XNAT archive mount point and the Docker Server mount point for your XNAT data.'),
                        XNAT.ui.panel.input.text({
                            name: 'path-translation-xnat-prefix',
                            label: 'XNAT Path Prefix',
                            addClass: 'path-prefix',
                            description: 'Enter the XNAT_HOME server path, i.e. "/data/xnat"'
                        }),
                        XNAT.ui.panel.input.text({
                            name: 'path-translation-docker-prefix',
                            label: 'Docker Server Path Prefix',
                            addClass: 'path-prefix',
                            description: 'Enter the Docker Server path to the XNAT_HOME mount, i.e. "/docker/my-data/XNAT"'
                        }),
                        spawn('p.divider', '<strong>Re-Pull Images on Init (Optional)</strong><br> Use this setting to force the Docker server to re-pull your images whenever the Apache Tomcat server is restarted. Images are only pulled if they are missing.'),
                        XNAT.ui.panel.input.switchbox({
                            name: 'pull-images-on-xnat-init',
                            label: 'Re-pull Images?',
                            onText: 'ON',
                            offText: 'OFF',
                            value: 'false'
                        }),
                        spawn('p.divider', '<strong>Container User (Optional)</strong><br>System user who will own process inside container. Use this if XNAT files are on a mount restricting permissions to certain users. ' +
                            'Value can be of the form user, user:group, uid, uid:gid, user:gid, or uid:group. ' +
                            '<br>If no value is set, container processes are run as the value set in the image; if no value is set in the image, the default is root.'),
                        XNAT.ui.panel.input.text({
                            name: 'container-user',
                            label: 'Container User'
                        }).element
                    ])
                );

                if (item && isDefined(item.host)) {
                    if (item['cert-path'] === 'null') item['cert-path'] = null;
                    $formContainer.find('form').setValues(item);
                }
            },
            buttons: [
                {
                    label: 'Save',
                    isDefault: true,
                    close: false,
                    action: function(obj){
                        var $form = obj.$modal.find('form');
                        var $host = $form.find('input[name=host]');
                        var pathPrefixes = $form.find('input.path-prefix').toArray();

                        $form.find(':input').removeClass('invalid');

                        var errors = [];
                        if (csValidator([$host]).length) errors = errors.concat(csValidator([$host]));
                        if (csMultiFieldValidator(pathPrefixes).length) errors = errors.concat(csMultiFieldValidator(pathPrefixes));

                        if (errors.length) {

                            XNAT.dialog.open({
                                title: 'Validation Error',
                                width: 300,
                                content: displayErrors(errors)
                            })
                        } else {
                            XNAT.dialog.closeAll();
                            if (isNew) {
                                XNAT.dialog.open({
                                    content: spawn('div',[
                                        spawn('p','This will replace your existing host definition. Are you sure you want to do this?'),
                                        spawn('p', { 'style': { 'font-weight': 'bold' }}, 'This action cannot be undone.')
                                    ]),
                                    buttons: [
                                        {
                                            label: 'OK',
                                            close: true,
                                            isDefault: true,
                                            action: function () {
                                                submitHostEditor($form);
                                            }
                                        },
                                        {
                                            label: 'Cancel',
                                            action: function(){
                                                XNAT.dialog.closeAll();
                                            }
                                        }
                                    ]
                                });
                            } else {
                                submitHostEditor($form);
                            }
                        }
                    }
                },
                {
                    label: 'Cancel',
                    close: true
                }
            ]
        });
    };

    function submitHostEditor($form){
        // validate path prefix fields


        $form.submitJSON({
            method: 'POST',
            url: containerHostUrl(),
            success: function(){
                containerHostManager.refreshTable();
                xmodal.closeAll();
                XNAT.ui.banner.top(2000, 'Saved.', 'success')
            }
        });
    }

    // create table for Container Hosts
    containerHostManager.table = function(container, callback){

        // initialize the table - we'll add to it below
        var chmTable = XNAT.table({
            className: 'container-hosts xnat-table',
            style: {
                width: '100%',
                marginTop: '15px',
                marginBottom: '15px'
            }
        });

        // add table header row
        chmTable.tr()
            .th({ addClass: 'left', html: '<b>Host Name</b>' })
            .th('<b>Host Path</b>')
            .th('<b>Default</b>')
            .th('<b>Swarm Mode</b>')
            .th('<b>Status</b>')
            .th('<b>Actions</b>');

        function editLink(item, text){
            return spawn('a.link|href=#!', {
                onclick: function(e){
                    e.preventDefault();
                    containerHostManager.dialog(item, false);
                }
            }, [['b', text]]);
        }

        function defaultToggle(item){
            var rdo = spawn('input.container-host-enabled', {
                type: 'radio',
                name: 'defaultHost',
                checked: 'checked',
                value: 'default',
                data: { id: item.id, name: item.name },
                onchange: function(){
                    // save the status when clicked
                    var radio = this;
                    xmodal.alert('Cannot set default server yet');
                }
            });
            return spawn('div.center', [rdo]);
        }

        function editButton(item) {
            return spawn('button.btn.sm.edit', {
                onclick: function(e){
                    e.preventDefault();
                    containerHostManager.dialog(item, false);
                }
            }, 'Edit');
        }

        function hostPingStatus(ping) {
            var status = {};
            if (ping !== undefined) {
                status = (ping) ? { label: 'OK', message: 'Ping Status: OK'} :  { label: 'Down', message: 'Ping Status: FALSE' };
            } else {
                status = { label: 'Error', message: 'No response to ping' };
            }
            return spawn('span', { title: status.message }, status.label);
        }

        containerHostManager.getAll().done(function(data){
            data = [].concat(data);
            data.forEach(function(item){
                chmTable.tr({ title: item.name, data: { id: item.id, host: item.host, certPath: item.certPath}})
                    .td([editLink(item, item.name)]).addClass('host')
                    .td([ spawn('div.center', [item.host]) ])
                    .td([ spawn('div.center', [defaultToggle(item)]) ])
                    .td([ spawn('div.center', item['swarm-mode'] ? 'ON' : 'OFF' )])
                    .td([ spawn('div.center', [hostPingStatus(item.ping)]) ])
                    .td([ spawn('div.center', [editButton(item)]) ])
            });

            if (container){
                $$(container).append(chmTable.table);
            }

            if (isFunction(callback)) {
                callback(chmTable.table);
            }

        });

        containerHostManager.$table = $(chmTable.table);

        return chmTable.table;
    };

    containerHostManager.compatibilityCheck = function(){
        XNAT.xhr.get(XNAT.url.restUrl('/xapi/containers/version'))
            .success(function(data){
                if (!data.compatible){
                    // add an error banner to the plugin settings page
                    $('.xnat-tab-content').prepend(
                        spawn('div.alert.container-service-version-check',
                            {style: {'margin-bottom': '2em' }},
                            '<strong>Plugin Compatibility Error:</strong> '+ data.message
                        )
                    );
                } else {
                    console.log('Container Service compatibility check: Passed', data);
                }
            })
            .fail(function(e){
                console.log('Failed compatibility check',e);
            });
    };

    containerHostManager.init = function(container){

        var $manager = $$(container||'div#container-host-manager');
        var $footer = $('#container-host-manager').parents('.panel').find('.panel-footer');

        containerHostManager.$container = $manager;

        $manager.append(containerHostManager.table());
        // containerHostManager.table($manager);

        var newReceiver = spawn('button.new-container-host.btn.btn-sm.submit', {
            html: 'New Container Host',
            onclick: function(){
                containerHostManager.dialog(null, true);
            }
        });


        // add the 'add new' button to the panel footer
        $footer.append(spawn('div.pull-right', [
            newReceiver
        ]));
        $footer.append(spawn('div.clear.clearFix'));

        containerHostManager.compatibilityCheck();

        return {
            element: $manager[0],
            spawned: $manager[0],
            get: function(){
                return $manager[0]
            }
        };
    };

    containerHostManager.refresh = containerHostManager.refreshTable = function(){
        containerHostManager.$table.remove();
        containerHostManager.table(null, function(table){
            containerHostManager.$container.prepend(table);
        });
    };

    containerHostManager.init();




    /* ===================== *
     * Image Host Management *
     * ===================== */

    console.log('imageHostManagement.js');

    var imageHostManager, imageHostList;

    XNAT.plugin.containerService.imageHostManager = imageHostManager =
        getObject(XNAT.plugin.containerService.imageHostManager || {});

    XNAT.plugin.containerService.imageHostList = imageHostList = [];

    function imageHostUrl(isDefault,appended){
        appended = isDefined(appended) ? '/' + appended : '';
        if (isDefault) {
            return restUrl('/xapi/docker/hubs' + appended + '?default='+isDefault)
        } else {
            return restUrl('/xapi/docker/hubs' + appended);
        }
    }

    // get the list of hosts
    imageHostManager.getHosts = imageHostManager.getAll = function(callback){
        callback = isFunction(callback) ? callback : function(){};
        return XNAT.xhr.get({
            url: imageHostUrl(),
            dataType: 'json',
            success: function(data){
                imageHostList = data;
                callback.apply(this, arguments);
            }
        });
    };

    // dialog to create/edit hosts
    imageHostManager.dialog = function(item, isNew){
        var tmpl = $('#image-host-editor-template').find('form');
        isNew = isNew || false;
        var doWhat = (isNew) ? 'Create' : 'Edit';
        item = item || {};

        XNAT.dialog.open({
            title: doWhat + ' Image Host',
            content: spawn('form'),
            width: 550,
            beforeShow: function(obj){
                var $formContainer = obj.$modal.find('.xnat-dialog-content');
                $formContainer.addClass('panel').find('form').append(tmpl.html());
                $formContainer.find('.panel-body').append(
                    XNAT.ui.panel.input.switchbox({
                        name: 'default',
                        label: 'Set Default Hub?',
                        value: 'false'
                    })
                );
                if (!isNew) {
                    $formContainer.find('.panel-body').append(
                        XNAT.ui.panel.input.hidden({
                            name: 'id',
                            id: 'hub-id'
                        })
                    );
                }
                if (item && isDefined(item.url)) {
                    $formContainer.find('form').setValues(item);
                }

            },
            buttons: [
                {
                    label: 'Save',
                    isDefault: true,
                    close: false,
                    action: function(obj){
                        var $form = obj.$modal.find('form');
                        var $url = $form.find('input[name=url]');
                        var $name = $form.find('input[name=name]');
                        var setDefault = $form.find('input[name=default]').val();

                        $form.find(':input').removeClass('invalid');

                        var errors = csValidator([$url,$name]);
                        if (errors.length) {
                            XNAT.dialog.open({
                                title: 'Validation Error',
                                width: 300,
                                content: displayErrors(errors)
                            })
                        } else {
                            xmodal.loading.open({ title: 'Validating host URL'});
                            $form.submitJSON({
                                method: 'POST',
                                url: (isNew) ? imageHostUrl(setDefault) : imageHostUrl(setDefault, item.id),
                                success: function () {
                                    imageHostManager.refreshTable();
                                    xmodal.loading.close();
                                    XNAT.dialog.closeAll();
                                    XNAT.ui.banner.top(2000, 'Saved.', 'success')
                                },
                                fail: function (e) {
                                    xmodal.loading.close();
                                    errorHandler(e, 'Could Not Update Image Host');
                                }
                            });
                        }
                    }
                }
            ]
        });

    };

    // create table for Image Hosts
    imageHostManager.table = function(imageHost, callback){

        // initialize the table - we'll add to it below
        var ihmTable = XNAT.table({
            className: 'image-hosts xnat-table',
            style: {
                width: '100%',
                marginTop: '15px',
                marginBottom: '15px'
            }
        });

        // add table header row
        ihmTable.tr()
            .th({ addClass: 'left', html: '<b>ID</b>' })
            .th('<b>Name</b>')
            .th('<b>URL</b>')
            .th('<b>Default</b>')
            .th('<b>Status</b>')
            .th('<b>Actions</b>');

        function editLink(item, text){
            return spawn('a.link|href=#!', {
                onclick: function(e){
                    e.preventDefault();
                    imageHostManager.dialog(item, false);
                }
            }, [['b', text]]);
        }

        function editButton(item) {
            return spawn('button.btn.sm.edit', {
                onclick: function(e){
                    e.preventDefault();
                    imageHostManager.dialog(item, false);
                }
            }, 'Edit');
        }

        function defaultToggle(item){
            var defaultVal = !!item.default;
            var rdo = spawn('input.image-host-enabled', {
                type: 'radio',
                name: 'defaultHub',
                checked: defaultVal,
                value: 'default',
                data: { id: item.id, name: item.name },
                onchange: function(){
                    // save the status when clicked
                    var radio = this;
                    defaultVal = radio.checked;
                    XNAT.xhr.post({
                        url: imageHostUrl(true,item.id),
                        success: function(){
                            radio.value = defaultVal;
                            radio.checked = 'checked';
                            imageHostManager.refreshTable();
                            XNAT.ui.banner.top(1000, '<b>' + item.name + '</b> set as default', 'success');
                        },
                        fail: function(e){
                            radio.checked = false;
                            imageHostManager.refreshTable();
                            errorHandler(e,'Could Not Set Default Image Host');
                        }
                    });
                }
            });
            return spawn('div.center', [rdo]);
        }

        function isDefault(status,valIfTrue,valIfFalse) {
            valIfFalse = valIfFalse || false;
            return (status) ? valIfTrue : valIfFalse;
        }

        function hubPingStatus(ping) {
            var status = {};
            if (ping !== undefined) {
                status = (ping) ? { label: 'OK', message: 'Ping Status: OK' } : { label: 'Down', message: 'Ping Status: False' };
            } else {
                status = { label: 'Error', message: 'No response to ping' };
            }
            return spawn('span',{ title: status.message }, status.label);
        }

        function deleteButton(item){
            return spawn('button.btn.sm.delete', {
                onclick: function(){
                    xmodal.confirm({
                        height: 220,
                        scroll: false,
                        content: "" +
                            "<p>Are you sure you'd like to delete the Image Host at <b>" + item.url + "</b>?</p>" +
                            "<p><b>This action cannot be undone.</b></p>",
                        okAction: function(){
                            XNAT.xhr.delete({
                                url: imageHostUrl(false,item.id),
                                success: function(){
                                    XNAT.ui.banner.top(1000, '<b>"'+ item.url + '"</b> deleted.', 'success');
                                    imageHostManager.refreshTable();
                                },
                                fail: function(e){
                                    errorHandler(e, 'Could Not Delete Image Host');
                                }
                            });
                        }
                    })
                },
                disabled: isDefault(item.default,"disabled"),
                title: isDefault(item.default,"Cannot delete the default hub","Delete Image Host")
            }, [ spawn('i.fa.fa-trash') ]);
        }

        imageHostManager.getAll().done(function(data){
            data = [].concat(data);
            data.forEach(function(item){
                ihmTable.tr({ title: item.name, data: { id: item.id, name: item.name, url: item.url}})
                    .td( item.id )
                    .td([ editLink(item, item.name) ]).addClass('name')
                    .td( item.url )
                    .td([ defaultToggle(item)] ).addClass('status')
                    .td([ spawn('div.center', [hubPingStatus(item.ping)]) ])
                    .td([ spawn('div.center', [editButton(item), spacer(10), deleteButton(item)]) ]);
            });

            if (imageHost){
                $$(imageHost).empty().append(ihmTable.table);
            }

            if (isFunction(callback)) {
                callback(ihmTable.table);
            }

        });

        imageHostManager.$table = $(ihmTable.table);

        return ihmTable.table;
    };

    imageHostManager.init = function(container){

        var $manager = $$(container||'div#image-host-manager');
        var $footer = $('#image-host-manager').parents('.panel').find('.panel-footer');

        imageHostManager.container = $manager;

        $manager.append(imageHostManager.table());
        // imageHostManager.table($manager);

        var newReceiver = spawn('button.new-image-host.btn.btn-sm.submit', {
            html: 'New Image Host',
            onclick: function(){
                imageHostManager.dialog(null, true);
            }
        });

        // add the 'add new' button to the panel footer
        $footer.append(spawn('div.pull-right', [
            newReceiver
        ]));
        $footer.append(spawn('div.clear.clearFix'));

        return {
            element: $manager[0],
            spawned: $manager[0],
            get: function(){
                return $manager[0]
            }
        };
    };

    imageHostManager.refresh = imageHostManager.refreshTable = function(container){
        var $manager = $$(container||'div#image-host-manager');

        imageHostManager.$table.remove();
        $manager.append('Verifying Host Status...');
        imageHostManager.table(null, function(table){
            $manager.empty().prepend(table);
        });
    };

    imageHostManager.init();




    /* ===================== *
     * Image List Management *
     * ===================== */

    console.log('imageListManagement.js');

    var imageListManager,
        imageFilterManager,
        addImage,
        commandList,
        commandListManager,
        commandDefinition,
        wrapperList,
        imageHubs;

    XNAT.plugin.containerService.imageListManager = imageListManager =
        getObject(XNAT.plugin.containerService.imageListManager || {});

    XNAT.plugin.containerService.imageFilterManager = imageFilterManager =
        getObject(XNAT.plugin.containerService.imageFilterManager || {});

    XNAT.plugin.containerService.addImage = addImage =
        getObject(XNAT.plugin.containerService.addImage || {});

    XNAT.plugin.containerService.commandList = commandList = [];

    XNAT.plugin.containerService.commandListManager = commandListManager =
        getObject(XNAT.plugin.containerService.commandListManager || {});

    XNAT.plugin.containerService.commandDefinition = commandDefinition =
        getObject(XNAT.plugin.containerService.commandDefinition || {});

    XNAT.plugin.containerService.imageHubs = imageHubs =
        getObject(XNAT.plugin.containerService.imageHubs || {});

    XNAT.plugin.containerService.wrapperList = wrapperList =
        getObject(XNAT.plugin.containerService.wrapperList || {});

    imageListManager.samples = [
        {
            "label": "Docker Hub",
            "image_id": "https://hub.docker.com",
            "enabled": true
        }
    ];
    imageListManager.images = {}; // populate this object via rest

    function imageUrl(appended,force){
        appended = (appended) ? '/' + appended : '';
        force = (force) ? '?force=true' : '';
        return restUrl('/xapi/docker/images' + appended + force);
    }

    function commandUrl(appended){
        appended = isDefined(appended) ? appended : '';
        return csrfUrl('/xapi/commands' + appended);
    }

    // get the list of images
    imageListManager.getImages = imageListManager.getAll = function(callback){
        callback = isFunction(callback) ? callback : function(){};
        return XNAT.xhr.get({
            url: imageUrl(),
            dataType: 'json',
            success: function(data){
                callback.apply(this, arguments);
            }
        });
    };

    commandListManager.getCommands = commandListManager.getAll = function(imageName,callback){
        callback = isFunction(callback) ? callback : function(){};
        return XNAT.xhr.get({
            url: (imageName) ? commandUrl('?image='+imageName) : commandUrl(),
            dataType: 'json',
            success: function(data){
                if (data) {
                    return data;
                }
                callback.apply(this, arguments);
            }
        });
    };

    commandDefinition.getCommand = function(id,callback){
        callback = isFunction(callback) ? callback : function(){};
        return XNAT.xhr.get({
            url: commandUrl('/'+id),
            dataType: 'json',
            success: function(data){
                if (data) {
                    return data;
                }
                callback.apply(this, arguments);
            }
        })
    };

    // dialog to add new images
    addImage.dialog = function(item){
        var tmpl = $('#add-image-template').find('form').clone();
        item = item || {};
        XNAT.dialog.open({
            title: 'Pull New Image',
            content: spawn('form'),
            width: 500,
            padding: 0,
            beforeShow: function(obj){
                var $formContainer = obj.$modal.find('.xnat-dialog-content');
                $formContainer.addClass('panel pad20').find('form').append(tmpl.html());

                if (item && isDefined(item.image)) {
                    $formContainer.setValues(item);
                }
                var $hubSelect = $formContainer.find('#hub-id');
                // query the cached list of image hubs
                if (imageHostList.length > 1) {
                    imageHostList.forEach(function(hub){
                        var option = '<option value="'+hub.id+'"';
                        if (hub.default) option += ' selected';
                        option += '>'+hub.name+'</option>';
                        $hubSelect.prop('disabled',false).append(option);
                    });
                } else {
                    $hubSelect.parents('.panel-element').hide();
                }
            },
            buttons: [
                {
                    label: 'Pull Image',
                    isDefault: true,
                    close: false,
                    action: function(obj){
                        var $form = obj.$modal.find('form');
                        var $image = $form.find('input[name=image]');
                        var $tag = $form.find('input[name=tag]');

                        if ($image.val().indexOf(':') > 0) {
                            // if the tag is included in the image title, move it to the tag field
                            var imageTitleParts = $image.val().split(':');
                            $image.val(imageTitleParts[0]);

                            if ($tag.val() === '') {
                                $tag.val(':' + imageTitleParts[1]);
                            }
                        }
                        else {
                            if ($tag.val() === '') $tag.val(':latest');
                        }

                        // validate form inputs, then pull them into the URI querystring and create an XHR request.
                        $form.find(':input').removeClass('invalid');

                        var errors = csValidator([$image,$tag]);
                        if (errors.length) {

                            XNAT.dialog.open({
                                title: 'Validation Error',
                                width: 300,
                                content: displayErrors(errors)
                            })
                        } else {
                            // stitch together the image and tag definition, if a tag value was specified.
                            if ($tag.val().length > 0 && $tag.val().indexOf(':') < 0) {
                                $tag.val(':' + $tag.val());
                            }
                            var imageName = $image.val() + $tag.val();

                            xmodal.loading.open({ title: 'Submitting Pull Request' });

                            XNAT.xhr.post({
                                url: csrfUrl('/xapi/docker/pull?save-commands=true&image='+imageName),
                                success: function() {
                                    xmodal.loading.close();
                                    XNAT.dialog.closeAll();
                                    imageListManager.refreshTable();
                                    commandConfigManager.refreshTable();
                                    XNAT.ui.banner.top(2000, 'Pull request complete.', 'success');
                                },
                                fail: function(e) {
                                    errorHandler(e, 'Could Not Pull Image');
                                }
                            })
                        }
                    }
                },
                {
                    label: 'Cancel',
                    close: true
                }
            ]
        });
    };

    // create a code editor dialog to view a command definition
    commandDefinition.dialog = function(commandDef,newCommand,imageName){
        var _source,_editor;
        if (!newCommand) {
            commandDef = commandDef || {};

            var dialogButtons = {
                update: {
                    label: 'Save',
                    isDefault: true,
                    action: function(){
                        var editorContent = _editor.getValue().code;
                        // editorContent = JSON.stringify(editorContent).replace(/\r?\n|\r/g,' ');

                        var url = commandUrl('/'+sanitizedVars['id']);

                        XNAT.xhr.postJSON({
                            url: url,
                            dataType: 'json',
                            data: editorContent,
                            success: function(){
                                imageListManager.refreshTable();
                                commandConfigManager.refreshTable();
                                xmodal.closeAll();
                                XNAT.ui.banner.top(2000, 'Command definition updated.', 'success');
                            },
                            fail: function(e){
                                errorHandler(e, 'Could Not Update', false);
                            }
                        });
                    }
                },
                close: { label: 'Cancel' }
            };

            // sanitize the command definition so it can be updated
            var sanitizedVars = {};
            ['id', 'hash'].forEach(function(v){
                sanitizedVars[v] = commandDef[v];
                delete commandDef[v];
            });
            // remove wrapper IDs as well
            commandDef.xnat.forEach(function(w,i){
                delete commandDef.xnat[i].id
            });

            _source = spawn ('textarea', JSON.stringify(commandDef, null, 4));

            _editor = XNAT.app.codeEditor.init(_source, {
                language: 'json'
            });

            _editor.openEditor({
                title: 'Edit Definition For ' + commandDef.name,
                classes: 'plugin-json',
                buttons: dialogButtons,
                height: 680,
                afterShow: function(dialog, obj){
                    obj.aceEditor.setReadOnly(false);
                    dialog.$modal.find('.body .inner').prepend(
                        spawn('div',[
                            spawn('p', 'Command ID: '+sanitizedVars['id']),
                            spawn('p', 'Hash: '+sanitizedVars['hash']),
                            spawn('p', [
                                'Command Info URL: ',
                                (commandDef['info-url']) ? spawn('a',{ href: commandDef['info-url'], html: commandDef['info-url'], target: '_blank' }) : 'n/a'
                            ])
                        ])
                    );
                }
            });
        } else {
            _source = spawn('textarea', '{}');

            _editor = XNAT.app.codeEditor.init(_source, {
                language: 'json'
            });

            _editor.openEditor({
                title: 'Add New Command to '+imageName,
                classes: 'plugin-json',
                buttons: {
                    create: {
                        label: 'Save Command',
                        isDefault: true,
                        action: function(){
                            var editorContent = _editor.getValue().code;

                            var url = (imageName) ? commandUrl('?image='+imageName) : commandUrl();

                            XNAT.xhr.postJSON({
                                url: url,
                                dataType: 'json',
                                data: editorContent,
                                success: function(obj){
                                    imageListManager.refreshTable();
                                    commandConfigManager.refreshTable();
                                    xmodal.close(obj.$modal);
                                    XNAT.ui.banner.top(2000, 'Command definition created.', 'success');
                                },
                                fail: function(e){
                                    errorHandler(e, 'Could Not Save', false);
                                }
                            });
                        }
                    },
                    close: {
                        label: 'Cancel'
                    }
                }
            });
        }
    };


    // create table for listing commands
    commandListManager.table = function(image){

        var imageName = image.tags[0];
        var imageId = image['imageSha'];
        var $commandListContainer = $(document).find('#'+imageId+'-commandlist');

        // initialize the table - we'll add to it below
        var clmTable = XNAT.table({
            className: 'enabled-commands xnat-table',
            style: {
                width: '100%'
            }
        });

        // add table header row
        clmTable.tr()
            .th({ addClass: 'left', html: '<b>Command</b>' })
            .th('<b>XNAT Actions</b>')
            .th('<b>Site-wide Config</b>')
            .th('<b>Version</b>')
            .th({ width: '100', html: '<b>Actions</b>' });

        function viewLink(item, text){
            return spawn('a.link|href=#!', {
                onclick: function(e){
                    e.preventDefault();
                    commandDefinition.getCommand(item.id).done(function(commandDef){
                        commandDefinition.dialog(commandDef, false);
                    });
                }
            }, [['b', text]]);
        }

        function viewCommandButton(item){
            return spawn('button.btn.sm', {
                onclick: function(e){
                    e.preventDefault();
                    commandDefinition.getCommand(item.id).done(function(commandDef){
                        commandDefinition.dialog(commandDef, false);
                    });
                }
            }, '<i class="fa fa-pencil" title="Edit Command"></i>');
        }

        function enabledCheckbox(item){
            var enabled = !!item.enabled;
            var ckbox = spawn('input.command-enabled', {
                type: 'checkbox',
                checked: enabled,
                value: enabled,
                data: { name: item.name },
                onchange: function(){
                    /*    // save the status when clicked
                     var checkbox = this;
                     enabled = checkbox.checked;
                     */
                }
            });

            return spawn('div.center', [
                spawn('label.switchbox|title=' + item.name, [
                    ckbox,
                    ['span.switchbox-outer', [['span.switchbox-inner']]]
                ])
            ]);
        }

        function deleteCommandButton(item){
            return spawn('button.btn.sm.delete', {
                onclick: function(){
                    xmodal.confirm({
                        height: 220,
                        scroll: false,
                        content: "" +
                            "<p>Are you sure you'd like to delete the <b>" + item.name + "</b> command definition?</p>" +
                            "<p><b>This action cannot be undone.</b></p>",
                        okAction: function(){
                            console.log('delete id ' + item.id);
                            XNAT.xhr.delete({
                                url: commandUrl('/'+item.id),
                                success: function(){
                                    console.log('"'+ item.name + '" command deleted');
                                    XNAT.ui.banner.top(1000, '<b>"'+ item.name + '"</b> deleted.', 'success');
                                    imageListManager.refreshTable();
                                    commandConfigManager.refreshTable();
                                    XNAT.plugin.containerService.historyTable.refresh();
                                }
                            });
                        }
                    })
                },
                title: 'Delete Command'
            }, [ spawn('i.fa.fa-trash') ]);
        }

        commandListManager.getAll(imageName).done(function(data) {
            if (data.length) {
                for (var i = 0, j = data.length; i < j; i++) {
                    var xnatActions, command = data[i];
                    if (command.xnat) {
                        xnatActions = [];
                        for (var k = 0, l = command.xnat.length; k < l; k++) {
                            var description = command.xnat[k].description;
                            if (command.xnat[k].contexts.length > 0) {
                                description = '<b>'+command.xnat[k].contexts.toString() + '</b>: ' + description;
                            }
                            xnatActions.push(spawn('li',description))
                        }
                        xnatActions = [ spawn('ul.imageActionList', xnatActions) ];
                    } else {
                        xnatActions = 'N/A';
                    }
                    clmTable.tr({title: command.name, data: {id: command.id, name: command.name, image: command.image}})
                        .td([viewLink(command, command.name)]).addClass('name')
                        .td(xnatActions)
                        .td('N/A')
                        .td(command.version)
                        .td([ spawn('div.center', [viewCommandButton(command), spacer(10), deleteCommandButton(command)]) ]);
                }

            } else {
                // create a handler when no command data is returned.
                clmTable.tr({title: 'No command data found'})
                    .td({colSpan: '5', html: 'No Commands Found'});
            }

            $commandListContainer.append(clmTable.table);

            if (data.length === 0) {
                $commandListContainer.parents('.imageContainer').addClass('no-commands hidden');
                imageListManager.images[imageId].hideable = true; // Store a parameter that tracks whether we have hidden this image in the list for use in toggling.

                imageFilterManager.refresh();
            }
        });
    };

    function hiddenImagesMessage(num, hidden){
        if (!hidden) return 'Hide Images With No Commands';
        if (num === 0) return 'No Images Hidden';
        if (num === 1) return 'One Image Hidden';
        return parseInt(num) + ' Images Hidden';
    }

    imageFilterManager.init = imageFilterManager.refresh = function(){

        var $footer = $('#image-filter-bar').parents('.panel').find('.panel-footer');

        // add the 'add new' button to the panel footer
        var newImage = spawn('button.new-image.btn.btn-sm.submit', {
            html: 'Add New Image',
            onclick: function(){
                addImage.dialog(null);
            }
        });
        $footer.empty().append(spawn('div.pull-right', [
            newImage
        ]));
        var $hideableImages = $(document).find('.imageContainer.no-commands');
        if ($hideableImages.length) {
            $footer.append(spawn('div.pull-right.pad5v',[
                spawn('a.show-hidden-images.pad20h', { href: '#!', data: { 'hidden': 'true' }},[
                    spawn('i.fa.fa-eye-slash.pad5h'),
                    spawn('span', hiddenImagesMessage($hideableImages.length, true))
                ])
            ]))
        }

        $footer.append(spawn('div.clear.clearFix'));
    };

    imageListManager.init = function(container){
        var $manager = $$(container||'div#image-list-container');

        function newCommandButton(image) {
            return spawn('button.btn.sm',{
                html: 'Add New Command',
                onclick: function(){
                    commandDefinition.dialog(null,true,image.tags[0])
                }
            });
        }

        function deleteImage(image,force) {
            var content;
            force = force || false;
            if (!force) {
                content = spawn('div',[
                    spawn('p','Are you sure you\'d like to delete the '+image.tags[0]+' image?'),
                    spawn('p', [ spawn('strong', 'This action cannot be undone.' )])
                ]);
            } else {
                content = spawn('p','Containers may have been run using '+image.tags[0]+'. Please confirm that you want to delete this image.');
            }
            XNAT.dialog.open({
                width: 400,
                content: content,
                buttons: [
                    {
                        label: 'OK',
                        isDefault: true,
                        close: false,
                        action: function(){
                            XNAT.xhr.delete({
                                url: imageUrl(image['image-id'],force),
                                success: function(){
                                    XNAT.ui.banner.top(1000, '<b>' + image.tags[0] + ' image deleted, along with its commands and configurations.', 'success');
                                    imageListManager.refreshTable();
                                    commandConfigManager.refreshTable();
                                    XNAT.plugin.containerService.historyTable.refresh();
                                    XNAT.dialog.closeAll();
                                },
                                fail: function(e){
                                    if (e.status === 500) {
                                        XNAT.dialog.closeAll();
                                        deleteImage(image,true);
                                    } else {
                                        errorHandler(e, 'Could Not Delete Image');
                                    }
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
        }

        function deleteImageButton(image) {
            return spawn('button.btn.sm',{
                onclick: function(){
                    deleteImage(image);
                }
            }, 'Delete Image');
        }

        imageListManager.container = $manager;

        imageListManager.getAll().done(function(data){
            if (data.length > 0) {
                data = data.sort(function(a,b){ if (a.tags.length && b.tags.length) return (a.tags[0] > b.tags[0]) ? 1 : -1; });

                data.forEach(function(imageInfo){
                    if (imageInfo.tags.length && imageInfo.tags[0] !== "<none>:<none>") {

                        imageInfo['imageSha'] = imageInfo['image-id'].substring(7); // cut out leading 'sha256:' from image ID for use as a HTML ID.

                        // add image to canonical list of images
                        imageListManager.images[imageInfo['imageSha']] = imageInfo;

                        $manager.append(spawn('div.imageContainer',[
                            spawn('h3.imageTitle',[
                                imageInfo.tags[0],
                                spawn( 'span.pull-right',[
                                    deleteImageButton(imageInfo)
                                ]),
                                spawn( 'span.pull-right.pad10h',[
                                    newCommandButton(imageInfo)
                                ])
                            ]),
                            spawn('div.clearfix.clear'),
                            spawn('div.imageCommandList',{ id: imageInfo['imageSha']+'-commandlist' })
                        ]));

                        // render the command list after the image summary div has been rendered, and deal with images with no commands at that point.
                        commandListManager.table(imageInfo);
                    } else {
                        console.log('Image ['+imageInfo['image-id']+'] has no tag information and was ignored.');
                    }
                })

            } else {
                $manager.append(spawn('p',['There are no images installed in this XNAT.']));
            }

        });
    };

    $(document).on('click','.show-hidden-images',function(e){
        // toggle visibility of hideable images
        e.preventDefault();
        var hidden = $(this).data('hidden');
        var $hideableImages = $('.imageContainer.no-commands');

        if (hidden){
            $(this).find('.fa').removeClass('fa-eye-slash').addClass('fa-eye');
            $(this)
                .data('hidden',false)
                .find('span').html(hiddenImagesMessage(null,false));
            $hideableImages.removeClass('hidden');
        } else {
            $(this).find('.fa').removeClass('fa-eye').addClass('fa-eye-slash');
            $(this)
                .data('hidden','true')
                .find('span').html(hiddenImagesMessage($hideableImages.length, true));
            $hideableImages.addClass('hidden');
        }
    });

    imageListManager.refresh = imageListManager.refreshTable = function(container){
        container = $$(container || 'div#image-list-container');
        container.html('');
        imageListManager.init();
        imageFilterManager.refresh();
    };

    imageFilterManager.init();
    imageListManager.init();




    /* ================================ *
     * Command Configuration Management *
     * ================================ */

    console.log('commandConfigManagement.js');

    var commandConfigManager,
        configDefinition;

    XNAT.plugin.containerService.commandConfigManager = commandConfigManager =
        getObject(XNAT.plugin.containerService.commandConfigManager || {});

    XNAT.plugin.containerService.configDefinition = configDefinition =
        getObject(XNAT.plugin.containerService.configDefinition || {});


    function configUrl(command,wrapperName,appended){
        appended = isDefined(appended) ? '?' + appended : '';
        if (!command || !wrapperName) return false;
        return csrfUrl('/xapi/commands/'+command+'/wrappers/'+wrapperName+'/config' + appended);
    }

    function configEnableUrl(commandObj,wrapperObj,flag){
        var command = commandObj.id,
            wrapperName = wrapperObj.name;
        return csrfUrl('/xapi/commands/'+command+'/wrappers/'+wrapperName+'/' + flag);
    }

    function deleteWrapperUrl(id){
        return csrfUrl('/xapi/wrappers/'+id);
    }

    commandConfigManager.getCommands = commandConfigManager.getAll = function(callback){

        callback = isFunction(callback) ? callback : function(){};
        return XNAT.xhr.get({
            url: commandUrl(),
            dataType: 'json',
            success: function(data){
                if (data) {
                    return data;
                }
                callback.apply(this, arguments);
            },
            fail: function (e) {
                errorHandler(e, 'Could Not Retrieve List of Commands');
            }
        });
    };

    commandConfigManager.getEnabledStatus = function(command,wrapper,callback){
        callback = isFunction(callback) ? callback : function(){};
        return XNAT.xhr.get({
            url: configEnableUrl(command,wrapper,'enabled'),
            success: function(data){
                if (data) {
                    return data;
                }
                callback.apply(this, arguments);
            },
            fail: function(e){
                errorHandler(e, 'Could Not Query Enabled Status');
            }
        });
    };

    configDefinition.getConfig = function(commandId,wrapperName,callback){
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

    configDefinition.table = function(config) {

        // initialize the table - we'll add to it below
        var cceditTable = XNAT.table({
            className: 'command-config-definition xnat-table '+config.type,
            style: {
                width: '100%',
                marginTop: '15px',
                marginBottom: '15px'
            }
        });

        function basicConfigInput(name,value,required) {
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
            cceditTable.tr()
                .th({ addClass: 'left', html: '<b>Input</b>' })
                .th('<b>Default Value</b>')
                .th('<b>Matcher Value</b>')
                .th('<b>User-Settable?</b>')
                .th('<b>Advanced?</b>');

            for (i in inputs) {
                var input = inputs[i];
                cceditTable.tr({ data: { input: i }, className: 'input' })
                    .td( { data: { key: 'key' }, addClass: 'left'}, i )
                    .td( { data: { key: 'property', property: 'default-value' }}, basicConfigInput('defaultVal',input['default-value']) )
                    .td( { data: { key: 'property', property: 'matcher' }}, basicConfigInput('matcher',input['matcher']) )
                    .td( { data: { key: 'property', property: 'user-settable' }}, [['div', [configCheckbox('userSettable',input['user-settable']) ]]])
                    .td( { data: { key: 'property', property: 'advanced' }}, [['div', [configCheckbox('advanced',input['advanced']) ]]]);

            }

        } else if (config.type === 'outputs') {
            var outputs = config.outputs;

            // add table header row
            cceditTable.tr()
                .th({ addClass: 'left', html: '<b>Output</b>' })
                .th({ addClass: 'left', width: '75%', html: '<b>Label</b>' });

            for (o in outputs) {
                var output = outputs[o];
                cceditTable.tr({ data: { output: o }, className: 'output' })
                    .td( { data: { key: 'key' }, addClass: 'left'}, o )
                    .td( { data: { key: 'property', property: 'label' }}, basicConfigInput('label',output['label']) );
            }

        }

        configDefinition.$table = $(cceditTable.table);

        return cceditTable.table;
    };


    configDefinition.dialog = function(commandId,wrapperName){
        // get command definition
        configDefinition.getConfig(commandId,wrapperName)
            .success(function(data){
                var tmpl = $('div#command-config-template');
                var tmplBody = $(tmpl).find('.panel-body').html('');

                var inputs = data.inputs;
                var outputs = data.outputs;

                tmplBody.spawn('h3','Inputs');
                tmplBody.append(configDefinition.table({ type: 'inputs', inputs: inputs }));

                tmplBody.spawn('h3','Outputs');
                tmplBody.append(configDefinition.table({ type: 'outputs', outputs: outputs }));

                XNAT.dialog.open({
                    title: 'Set Config Values',
                    content: tmpl.html(),
                    width: 850,
                    beforeShow: function(obj){
                        var $panel = obj.$modal.find('#config-viewer-panel');
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
                                var $panel = obj.$modal.find('#config-viewer-panel');
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
                                        console.log('"' + wrapperName + '" updated');
                                        XNAT.ui.banner.top(1000, '<b>"' + wrapperName + '"</b> updated.', 'success');
                                        XNAT.dialog.closeAll();
                                    },
                                    fail: function(e){ errorHandler(e, 'Could Not Update Config Definition'); }
                                });
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
                errorHandler(e, 'Could Not Open Config Definition');
            });

    };


    commandConfigManager.table = function(){

        // initialize the table - we'll add to it below
        var ccmTable = XNAT.table({
            className: 'sitewide-command-configs xnat-table',
            style: {
                width: '100%',
                marginTop: '15px',
                marginBottom: '15px'
            }
        });

        // add table header row
        ccmTable.tr()
            .th({ addClass: 'left', html: '<b>XNAT Command Label</b>' })
            .th('<b>Container</b>')
            .th('<b>Enabled</b>')
            .th({ width: 170, html: '<b>Actions</b>' });

        // add master switch
        ccmTable.tr({ 'style': { 'background-color': '#f3f3f3' }})
            .td({className: 'name', html: 'Enable / Disable All Commands', colSpan: 2 })
            .td([ spawn('div',[masterCommandCheckbox()]) ])
            .td();

        function viewLink(item, wrapper){
            var label = (wrapper.description.length) ?
                wrapper.description :
                wrapper.name;
            return spawn('a.link|href=#!', {
                onclick: function(e){
                    e.preventDefault();
                    configDefinition.dialog(item.id, wrapper.name, false);
                }
            }, [['b', label]]);
        }

        function editConfigButton(item,wrapper){
            return spawn('button.btn.sm', {
                onclick: function(e){
                    e.preventDefault();
                    configDefinition.dialog(item.id, wrapper.name, false);
                }
            }, 'Set Defaults');
        }

        function deleteConfigButton(wrapper){
            return spawn('button.btn.sm', {
                onclick: function(e){
                    e.preventDefault();
                    xmodal.confirm({
                        title: 'Delete '+wrapper.name,
                        content: 'Are you sure you want to delete this command from your XNAT site? This will cause any execution of this command to be listed as "Unknown" in your Command History table.',
                        okAction: function(){
                            XNAT.xhr.delete({
                                url: deleteWrapperUrl(wrapper.id),
                                success: function(){
                                    XNAT.ui.banner.top(1000, '<b>'+wrapper.name+'</b> deleted from site', 'success');
                                    commandConfigManager.refreshTable();
                                    XNAT.plugin.containerService.historyTable.refresh();
                                    imageListManager.refresh();
                                },
                                fail: function(e){
                                    errorHandler(e, 'Could Not Delete Command Configuration');
                                }
                            });
                        }
                    })
                },
                title: 'Delete Command Configuration'
            }, [ spawn('i.fa.fa-trash') ])
        }

        function enabledCheckbox(command,wrapper){
            commandConfigManager.getEnabledStatus(command,wrapper).done(function(data){
                var enabled = wrapperList[wrapper.id].enabled = data; // update internal wrapper list
                $('#wrapper-'+wrapper.id+'-enable').prop('checked',enabled);
                commandConfigManager.setMasterEnableSwitch();
            });

            var ckbox = spawn('input.config-enabled.wrapper-enable', {
                type: 'checkbox',
                checked: false,
                value: 'true',
                id: 'wrapper-'+wrapper.id+'-enable',
                data: { name: wrapper.name },
                onchange: function(){
                    // save the status when clicked
                    var checkbox = this;
                    enabled = checkbox.checked;
                    var enabledFlag = (enabled) ? 'enabled' : 'disabled';

                    XNAT.xhr.put({
                        url: configEnableUrl(command,wrapper,enabledFlag),
                        success: function(){
                            var status = (enabled ? ' enabled' : ' disabled');
                            checkbox.value = enabled;
                            XNAT.ui.banner.top(1000, '<b>' + wrapper.name+ '</b> ' + status, 'success');
                            wrapperList[wrapper.id].enabled = (enabled);
                        },
                        fail: function(e){
                            errorHandler(e, 'Could Not Set '+status.toUpperCase()+' Status');
                        }
                    });

                    commandConfigManager.setMasterEnableSwitch();
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

        commandConfigManager.getAll().done(function(data) {
            // populate commandList[]
            XNAT.plugin.containerService.commandList = data;

            if (data && data.length) {
                for (var i = 0, j = data.length; i < j; i++) {
                    var command = data[i];
                    if (command.xnat) {
                        for (var k = 0, l = command.xnat.length; k < l; k++) {
                            var wrapper = command.xnat[k];
                            ccmTable.tr({title: wrapper.name, data: {wrapperid: wrapper.id, commandid: command.id, name: wrapper.name, image: command.image}})
                                .td([ viewLink(command, wrapper) ]).addClass('name')
                                .td([ spawn('span.truncate.truncate200', command.image ) ])
                                .td([ spawn('div', [enabledCheckbox(command,wrapper)]) ])
                                .td([ spawn('div.center', [editConfigButton(command,wrapper), spacer(10), deleteConfigButton(wrapper)]) ]);

                            // populate wrapperList{}
                            XNAT.plugin.containerService.wrapperList[wrapper.id] = {
                                'id': wrapper.id,
                                'name': wrapper.name,
                                'description': wrapper.description,
                                'contexts': wrapper.contexts
                            };
                        }
                    }
                }
            } else {
                // create a handler when no command data is returned.
                return spawn('p','No XNAT-enabled Commands Found');
            }

            commandAutomationAdmin.init();  // initialize automation table after command config table data loads
        });

        commandConfigManager.$table = $(ccmTable.table);

        return ccmTable.table;
    };

    // examine all command toggles and set master switch to "ON" if all are checked
    commandConfigManager.setMasterEnableSwitch = function(){
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

    commandConfigManager.refresh = commandConfigManager.refreshTable = function(container){
        var $manager = $$(container||'div#command-config-list-container');

        $manager.html('');
        $manager.append(commandConfigManager.table());
        commandConfigManager.setMasterEnableSwitch();
    };

    commandConfigManager.init = function(container){
        var $manager = $$(container||'div#command-config-list-container');

        commandConfigManager.container = $manager;

        $manager.append(commandConfigManager.table());
    };

    commandConfigManager.init();

    /* ================================= *
     * Command Automation Administration *
     * ================================= */

    console.log('commandAutomationAdmin.js');

    var commandAutomationAdmin, projectList;

    XNAT.plugin.containerService.commandAutomation = commandAutomationAdmin =
        getObject(XNAT.plugin.containerService.commandAutomation || {});

    XNAT.plugin.containerService.projectList = projectList = [];

    function getProjectListUrl(){
        return restUrl('/data/projects?format=json');
    }
    function getCommandAutomationUrl(appended){
        appended = (appended) ? '?'+appended : '';
        return restUrl('/xapi/commandeventmapping' + appended);
    }
    function commandAutomationIdUrl(id){
        return csrfUrl('/xapi/commandeventmapping/' + id );
    }

    commandAutomationAdmin.getProjects = function(callback){
        callback = isFunction(callback) ? callback : function(){};

        return XNAT.xhr.getJSON({
            url: getProjectListUrl(),
            success: function(data){
                if (data){
                    projectList = data.ResultSet.Result;
                    return projectList;
                }
                callback.apply(this, arguments);
            },
            fail: function(e){
                errorHandler(e,'Could Not Get Project List.');
            }
        });
    };

    commandAutomationAdmin.deleteAutomation = function(id){
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
                                XNAT.plugin.containerService.commandAutomation.init('refresh');
                            }
                        }
                    ]
                })
            },
            fail: function(e){
                errorHandler(e, 'Could Not Delete Command Automation');
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
                    XNAT.plugin.containerService.commandAutomation.init('refresh');
                },
                fail: function(e){
                    errorHandler(e, 'Could Not Delete Command Automation');
                }
            })
        }
    });

    commandAutomationAdmin.addDialog = function(){
        // get all commands and wrappers, then open a dialog to allow user to configure an automation.

        function projectSelector(name,label){
            name = (name) ? name : 'project';
            label = (label) ? label : 'For Project';
            var projectOptions = {};

            // build options object for standard XNAT panel select
            projectList.forEach(function(project){
                projectOptions[project.ID] = project['secondary_ID'];
            });

            return XNAT.ui.panel.select.single({
                name: name,
                label: label,
                options: projectOptions
            });
        }

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

        function eventCommandSelector(options,description){
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

        // build selector for commands that can be automated
        commandList = XNAT.plugin.containerService.commandList;
        if (commandList.length && projectList.length && Object.keys(wrapperList).length) {

            var commandOptions = [];
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
                    // iterate through each wrapper definition. Cross-check enabled status against internal wrapper list.
                    if (wrapperList[wrapper.id].enabled) {
                        commandOptions.push({
                            label: wrapper.name,
                            value: wrapper.name,
                            'command-id': command.id,
                            contexts: wrapper.contexts
                        });
                    }
                });
            });

            if (Object.keys(commandOptions).length > 0) {
                XNAT.ui.dialog.open({
                    title: 'Create Command Automation',
                    width: 500,
                    content: '<div class="panel pad20"></div>',
                    beforeShow: function(obj){
                        // populate form elements
                        var panel = obj.$modal.find('.panel');
                        panel.append( spawn('p','Please enter values for each field.') );
                        panel.append( projectSelector() );
                        panel.append( eventSelector(eventOptions) );
                        panel.append( eventCommandSelector(commandOptions)
                        );
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
                                    project = panel.find('select[name=project]').find('option:selected').val(),
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
                                            XNAT.plugin.containerService.commandAutomation.init('refresh');
                                        },
                                        fail: function(e){
                                            errorHandler(e,'Could Not Create Command Automation');
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
                errorHandler('No enabled commands were found. Could not create an automation.', 'Could not create automation');
            }
        }

    };

    commandAutomationAdmin.table = function(){
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
            .th('<b>Project</b>')
            .th('<b>Event</b>')
            .th('<b>Command</b>')
            .th('<b>Created By</b>')
            .th('<b>Enabled</b>')
            .th('<b>Action</b>');

        function displayDate(timestamp){
            var d = new Date(timestamp);
            return d.toISOString().replace('T',' ').replace('Z',' ');
        }

        function deleteAutomationButton(id){
            return spawn( 'button.deleteAutomationButton', {
                data: {id: id},
                title: 'Delete Command Automation'
            }, [ spawn('i.fa.fa-trash') ]);
        }

        XNAT.xhr.getJSON({
            url: getCommandAutomationUrl(),
            fail: function(e){
                errorHandler(e, 'Could Not Retrieve Command Automation List');
            },
            success: function(data){
                // data returns an array of known command event mappings
                if (data.length){
                    data.forEach(function(mapping){
                        caTable.tr()
                            .td( '<b>'+mapping['id']+'</b>' )
                            .td( mapping['project'] )
                            .td( mapping['event-type'] )
                            .td( mapping['xnat-command-wrapper'] )
                            .td( mapping['subscription-user-name'] )
                            .td( mapping['enabled'] )
                            .td([ spawn('div.center', [ deleteAutomationButton(mapping['id']) ]) ])
                    });

                } else {
                    caTable.tr()
                        .td({ colSpan: '7', html: 'No command event mappings exist on this site.' });
                }
            }
        });

        commandAutomationAdmin.$table = $(caTable.table);

        return caTable.table;
    };

    commandAutomationAdmin.init = function(){
        // initialize the list of command automations
        var manager = $('#command-automation-admin-list');
        var $footer = manager.parents('.panel').find('.panel-footer');

        manager.html('');
        $footer.html('');

        // only show a list of automations if any commands and wrappers have been defined.
        if (Object.keys(wrapperList).length > 0) {
            manager.append(commandAutomationAdmin.table());

            commandAutomationAdmin.getProjects().done(function(){
                var newAutomation = spawn('button.new-command-automation.btn.btn-sm.submit', {
                    html: 'Add New Command Automation',
                    onclick: function(){
                        XNAT.plugin.containerService.commandAutomation.addDialog();
                    }
                });

                // add the 'add new' button to the panel footer
                $footer.append(spawn('div.pull-right', [
                    newAutomation
                ]));
                $footer.append(spawn('div.clear.clearFix'));
            });

        } else {
            manager.append(spawn('p',{'style' : { 'margin-top': '1em'} },'There are no commands that can be automated. Please navigate to the Images &amp; Commands tab'))
        }

    };

    // Automation panel gets initialized after command config table loads.

}));

$(document).ready(function(){
    XNAT.plugin.containerService.historyTable.init();            // initialize the command history table after the command list is loaded and the page is rendered
});