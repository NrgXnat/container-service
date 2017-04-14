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

    function spacer(width){
        return spawn('i.spacer', {
            style: {
                display: 'inline-block',
                width: width + 'px'
            }
        })
    }

    function errorHandler(e, title){
        console.log(e);
        title = (title) ? 'Error Found: '+ title : 'Error';
        xmodal.alert({
            title: title,
            content: '<p><strong>Error ' + e.status + ': '+ e.statusText+'</strong></p><p>' + e.responseText + '</p>',
            okAction: function () {
                xmodal.closeAll();
            }
        });
    }


/* ====================== *
 * Container Host Manager *
 * ====================== */

    console.log('containerHostManager.js');

    var containerHostManager,
        undefined,
        rootUrl = XNAT.url.rootUrl;

    XNAT.plugin =
        getObject(XNAT.plugin || {});

    XNAT.plugin.containerService =
        getObject(XNAT.plugin.containerService || {});

    XNAT.plugin.containerService.containerHostManager = containerHostManager =
        getObject(XNAT.plugin.containerService.containerHostManager || {});

    containerHostManager.samples = [
        {
            "host": "unix:///var/run/docker.sock",
            "cert-path": ""
        }
    ];

    function containerHostUrl(appended){
        appended = isDefined(appended) ? '/' + appended : '';
        return rootUrl('/xapi/docker/server' + appended);
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
        var tmpl = $('#container-host-editor-template');
        var doWhat = !item ? 'New' : 'Edit';
        isNew = firstDefined(isNew, doWhat === 'New');
        item = item || {};
        xmodal.open({
            title: doWhat + ' Container Server Host',
            template: tmpl.clone(),
            width: 350,
            height: 250,
            scroll: false,
            padding: '0',
            beforeShow: function(obj){
                var $form = obj.$modal.find('form');
                if (item && isDefined(item.host)) {
                    $form.setValues(item);
                }
            },
            okClose: false,
            okLabel: 'Save',
            okAction: function(obj){
                // the form panel is 'containerHostTemplate' in site-admin-element.yaml
                var $form = obj.$modal.find('form');
                var $host = $form.find('input[name=host]');
                $form.submitJSON({
                    method: isNew ? 'POST' : 'PUT',
                    url: isNew ? containerHostUrl() : containerHostUrl(item.id),
                    validate: function(){

                        $form.find(':input').removeClass('invalid');

                        var errors = 0;
                        var errorMsg = 'Errors were found with the following fields: <ul>';

                        [$host].forEach(function($el){
                            var el = $el[0];
                            if (!el.value) {
                                errors++;
                                errorMsg += '<li><b>' + el.title + '</b> is required.</li>';
                                $el.addClass('invalid');
                            }
                        });

                        errorMsg += '</ul>';

                        if (errors > 0) {
                            xmodal.message('Errors Found', errorMsg, { height: 300 });
                        }

                        return errors === 0;

                    },
                    success: function(){
                        containerHostManager.refreshTable();
                        xmodal.close(obj.$modal);
                        XNAT.ui.banner.top(2000, 'Saved.', 'success')
                    }
                });
            }
        });
    };

    // create table for Container Hosts
    containerHostManager.table = function(container, callback){

        // initialize the table - we'll add to it below
        var chTable = XNAT.table({
            className: 'container-hosts xnat-table',
            style: {
                width: '100%',
                marginTop: '15px',
                marginBottom: '15px'
            }
        });

        // add table header row
        chTable.tr()
            .th({ addClass: 'left', html: '<b>Host</b>' })
            .th('<b>Server Type</b>')
            .th('<b>Default</b>')
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
            var defaultVal = !!item.default;
            var rdo = spawn('input.container-host-enabled', {
                type: 'radio',
                name: 'defaultHost',
                checked: defaultVal,
                value: 'default',
                data: { id: item.id, name: item.name },
                onchange: function(){
                    // save the status when clicked
                    var radio = this;
                    xmodal.alert('Cannot set default server yet');
                    /*
                    defaultVal = radio.checked;
                    XNAT.xhr.post({
                        url: containerHostUrl(item.id+'?default=true'),
                        success: function(){
                            radio.value = defaultVal;
                            radio.checked = 'checked';
                            containerHostManager.refreshTable();
                            XNAT.ui.banner.top(1000, '<b>' + item.name + '</b> set as default', 'success');
                        },
                            fail: function(e){
                            radio.checked = false;
                            containerHostManager.refreshTable();
                            errorHandler(e);
                        }
                    });
                    */
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

        function deleteButton(item){
            return spawn('button.btn.sm.delete', {
                onclick: function(){
                    xmodal.confirm({
                        height: 220,
                        scroll: false,
                        content: "" +
                        "<p>Are you sure you'd like to delete the Container Host at <b>" + item.host + "</b>?</p>" +
                        "<p><b>This action cannot be undone.</b></p>",
                        okAction: function(){
                            XNAT.xhr.delete({
                                url: containerHostUrl(item.id),
                                success: function(){
                                    XNAT.ui.banner.top(1000, '<b>"'+ item.host + '"</b> deleted.', 'success');
                                    containerHostManager.refreshTable();
                                }
                            });
                        }
                    })
                }
            }, 'Delete');
        }

        containerHostManager.getAll().done(function(data){
            data = [].concat(data);
            data.forEach(function(item){
                chTable.tr({ title: item.host, data: { id: item.id, host: item.host, certPath: item.certPath}})
                    .td([editLink(item, item.host)]).addClass('host')
                    .td([['div.center', ['Docker']]])
                    .td([['div.center', [defaultToggle(item)]]])
                    .td([['div.center', [editButton(item), spacer(10), deleteButton(item)]]]);
            });

            if (container){
                $$(container).append(chTable.table);
            }

            if (isFunction(callback)) {
                callback(chTable.table);
            }

        });

        containerHostManager.$table = $(chTable.table);

        return chTable.table;
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

    var imageHostManager;

    XNAT.plugin.containerService.imageHostManager = imageHostManager =
        getObject(XNAT.plugin.containerService.imageHostManager || {});

    imageHostManager.samples = [
        {
            "name": "Docker Hub",
            "url": "https://hub.docker.com",
            "enabled": true
        }
    ];

    function imageHostUrl(isDefault,appended){
        appended = isDefined(appended) ? '/' + appended : '';
        if (isDefault) {
            return rootUrl('/xapi/docker/hubs' + appended + '?default='+isDefault)
        } else {
            return rootUrl('/xapi/docker/hubs' + appended);
        }
    }

    // get the list of hosts
    imageHostManager.getHosts = imageHostManager.getAll = function(callback){
        callback = isFunction(callback) ? callback : function(){};
        return XNAT.xhr.get({
            url: imageHostUrl(),
            dataType: 'json',
            success: function(data){
                imageHostManager.hosts = data;
                callback.apply(this, arguments);
            }
        });
    };

    // dialog to create/edit hosts
    imageHostManager.dialog = function(item, isNew){
        var tmpl = $('#image-host-editor-template');
        var doWhat = !item ? 'New' : 'Edit';
        isNew = firstDefined(isNew, doWhat === 'New');
        item = item || {};
        xmodal.open({
            title: doWhat + ' Image Hub',
            template: tmpl.clone(),
            width: 450,
            height: 300,
            scroll: false,
            padding: '0',
            beforeShow: function(obj){
                var $form = obj.$modal.find('form');
                if (item && isDefined(item.url)) {
                    $form.setValues(item);
                }
            },
            okClose: false,
            okLabel: 'Save',
            okAction: function(obj){
                // the form panel is 'imageHostTemplate' in containers-elements.yaml
                var $form = obj.$modal.find('form');
                var $url = $form.find('input[name=url]');
                var $name = $form.find('input[name=name]');
                var isDefault = $form.find('input[name=default]').val();
                $form.submitJSON({
                    method: 'POST',
                    url: (isNew) ? containerHostUrl(isDefault) : containerHostUrl(isDefault,item.id),
                    validate: function(){

                        $form.find(':input').removeClass('invalid');

                        var errors = 0;
                        var errorMsg = 'Errors were found with the following fields: <ul>';

                        [$name,$url].forEach(function($el){
                            var el = $el[0];
                            if (!el.value) {
                                errors++;
                                errorMsg += '<li><b>' + el.title + '</b> is required.</li>';
                                $el.addClass('invalid');
                            }
                        });

                        errorMsg += '</ul>';

                        if (errors > 0) {
                            xmodal.message('Errors Found', errorMsg, { height: 300 });
                        }

                        return errors === 0;

                    },
                    success: function(){
                        imageHostManager.refreshTable();
                        xmodal.close(obj.$modal);
                        XNAT.ui.banner.top(2000, 'Saved.', 'success')
                    },
                    fail: function(e){
                        errorHandler(e);
                    }
                });
            }
        });
    };

    // create table for Image Hosts
    imageHostManager.table = function(imageHost, callback){

        // initialize the table - we'll add to it below
        var chTable = XNAT.table({
            className: 'image-hosts xnat-table',
            style: {
                width: '100%',
                marginTop: '15px',
                marginBottom: '15px'
            }
        });

        // add table header row
        chTable.tr()
            .th({ addClass: 'left', html: '<b>ID</b>' })
            .th('<b>Name</b>')
            .th('<b>URL</b>')
            .th('<b>Default</b>')
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
                            errorHandler(e,'Could Not Set Default Hub');
                        }
                    });
                }
            });
            return spawn('div.center', [rdo]);
        }

        function isDefault(status,valIfTrue) {
            return (status) ? valIfTrue : false;
        }

        function deleteButton(item){
            return spawn('button.btn.sm.delete', {
                onclick: function(){
                    xmodal.confirm({
                        height: 220,
                        scroll: false,
                        content: "" +
                        "<p>Are you sure you'd like to delete the Container Host at <b>" + item.url + "</b>?</p>" +
                        "<p><b>This action cannot be undone.</b></p>",
                        okAction: function(){
                            XNAT.xhr.delete({
                                url: imageHostUrl(false,item.id),
                                success: function(){
                                    XNAT.ui.banner.top(1000, '<b>"'+ item.url + '"</b> deleted.', 'success');
                                    imageHostManager.refreshTable();
                                },
                                fail: function(e){
                                    errorHandler(e);
                                }
                            });
                        }
                    })
                },
                disabled: isDefault(item.default,"disabled"),
                title: isDefault(item.default,"Cannot delete the default hub")
            }, 'Delete');
        }

        imageHostManager.getAll().done(function(data){
            data = [].concat(data);
            data.forEach(function(item){
                chTable.tr({ title: item.name, data: { id: item.id, name: item.name, url: item.url}})
                    .td(['div.center'],item.id)
                    .td([editLink(item, item.name)]).addClass('name')
                    .td(item.url)
                    .td([defaultToggle(item)]).addClass('status')
                    .td([['div.center', [editButton(item), spacer(10), deleteButton(item)]]]);
            });

            if (imageHost){
                $$(imageHost).append(chTable.table);
            }

            if (isFunction(callback)) {
                callback(chTable.table);
            }

        });

        imageHostManager.$table = $(chTable.table);

        return chTable.table;
    };

    imageHostManager.init = function(container){

        var $manager = $$(container||'div#image-host-manager');
        var $footer = $('#image-host-manager').parents('.panel').find('.panel-footer');

        imageHostManager.container = $manager;

        $manager.append(imageHostManager.table());
        // imageHostManager.table($manager);

        var newReceiver = spawn('button.new-image-host.btn.btn-sm.submit', {
            html: 'New Image Hub',
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
        imageHostManager.table(null, function(table){
            $manager.prepend(table);
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
        commandListManager,
        commandDefinition,
        imageHubs;

    XNAT.plugin.containerService.imageListManager = imageListManager =
        getObject(XNAT.plugin.containerService.imageListManager || {});

    XNAT.plugin.containerService.imageFilterManager = imageFilterManager =
        getObject(XNAT.plugin.containerService.imageFilterManager || {});

    XNAT.plugin.containerService.addImage = addImage =
        getObject(XNAT.plugin.containerService.addImage || {});

    XNAT.plugin.containerService.commandListManager = commandListManager =
        getObject(XNAT.plugin.containerService.commandListManager || {});

    XNAT.plugin.containerService.commandDefinition = commandDefinition =
        getObject(XNAT.plugin.containerService.commandDefinition || {});

    XNAT.plugin.containerService.imageHubs = imageHubs =
        getObject(XNAT.plugin.containerService.imageHubs || {});

    imageListManager.samples = [
        {
            "label": "Docker Hub",
            "image_id": "https://hub.docker.com",
            "enabled": true
        }
    ];

    function imageUrl(appended){
        appended = isDefined(appended) ? '/' + appended : '';
        return rootUrl('/xapi/docker/images' + appended);
    }

    function commandUrl(appended){
        appended = isDefined(appended) ? appended : '';
        return rootUrl('/xapi/commands' + appended);
    }

    function imagePullUrl(appended,hubId){
        if (isDefined(hubId)) {
            return rootUrl('/xapi/docker/hubs/'+ hubId +'pull?' + appended);
        } else {
            return rootUrl('/xapi/docker/pull?' + appended);
        }
    }

    // get the list of images
    imageListManager.getImages = imageListManager.getAll = function(callback){
        callback = isFunction(callback) ? callback : function(){};
        return XNAT.xhr.get({
            url: imageUrl(),
            dataType: 'json',
            success: function(data){
                imageListManager.hosts = data;
                callback.apply(this, arguments);
            }
        });
    };

    // get the list of image hubs
    imageHubs.getHubs = imageHubs.getAll = function(callback){
        callback = isFunction(callback)? callback : function(){};
        return XNAT.xhr.get({
            url: '/xapi/docker/hubs',
            dataType: 'json',
            success: function(data){
                imageHubs.hubs = data;
                callback.apply(this, arguments);
            }
        });
    };

    commandListManager.getCommands = commandListManager.getAll = function(imageName,callback){
        /*
         if (imageName) {
         imageName = imageName.split(':')[0]; // remove any tag definition (i.e. ':latest') in the image name
         imageName = imageName.replace("/","%2F"); // convert slashes in image names to URL-ASCII equivalent
         }
         */
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
        var tmpl = $('#add-image-template');
        var pullUrl;
        item = item || {};
        xmodal.open({
            title: 'Pull New Image',
            template: tmpl.clone(),
            width: 450,
            height: 400,
            scroll: false,
            padding: '0',
            beforeShow: function(obj){
                var $form = obj.$modal.find('form');
                if (item && isDefined(item.image)) {
                    $form.setValues(item);
                }
                var $hubSelect = $form.find('#hub-id');
                // get list of image hubs and select the default hub
                imageHubs.getAll().done(function(hubs){
                    if (hubs.length > 1) {
                        hubs.forEach(function(item){
                            var option = '<option value="'+item.id+'"';
                            if (item.default) option += ' selected';
                            option += '>'+item.name+'</option>';
                            $hubSelect.prop('disabled',false).append(option);
                        });
                    }
                });
            },
            okClose: false,
            okLabel: 'Pull Image',
            okAction: function(obj){
                // the form panel is 'imageListTemplate' in containers-elements.yaml
                var $form = obj.$modal.find('form');
                var $image = $form.find('input[name=image]');
                var $tag = $form.find('input[name=tag]');

                // validate form inputs, then pull them into the URI querystring and create an XHR request.
                $form.find(':input').removeClass('invalid');

                var errors = 0;
                var errorMsg = 'Errors were found with the following fields: <ul>';

                [$image].forEach(function($el){
                    var el = $el[0];
                    if (!el.value) {
                        errors++;
                        errorMsg += '<li><b>' + el.title + '</b> is required.</li>';
                        $el.addClass('invalid');
                    }
                });

                errorMsg += '</ul>';

                if (errors > 0) {
                    xmodal.message('Errors Found', errorMsg, { height: 300 });
                } else {
                    // stitch together the image and tag definition, if a tag value was specified.
                    if ($tag.val().length > 0 && $tag.val().indexOf(':') < 0) {
                        $tag.val(':' + $tag.val());
                    }
                    var imageName = $image.val() + $tag.val();

                    xmodal.loading.open({title: 'Submitting Pull Request',height: '110'});

                    XNAT.xhr.post({ url: '/xapi/docker/pull?save-commands=true&image='+imageName })
                        .success(
                            function() {
                                xmodal.closeAll();
                                imageListManager.refreshTable();
                                XNAT.ui.banner.top(2000, 'Pull request complete.', 'success');
                            })
                        .fail(
                            function(e) {
                                errorHandler(e, 'Could Not Pull Image');
                            }
                        );
                }
            }
        });
    };

    // create a read-only code editor dialog to view a command definition
    commandDefinition.dialog = function(commandDef,newCommand){
        var _source,_editor;
        if (!newCommand) {
            commandDef = commandDef || {};

            _source = spawn('textarea', JSON.stringify(commandDef, null, 4));

            _editor = XNAT.app.codeEditor.init(_source, {
                language: 'json'
            });

            _source.openEditor({
                title: commandDef.name,
                classes: 'plugin-json',
                footerContent: '(read-only)',
                buttons: {
                    close: { label: 'Close' },
                    info: {
                        label: 'View Command Info',
                        action: function(){
                            window.open(commandDef['info-url'],'infoUrl');
                        }
                    }
                },
                afterShow: function(dialog, obj){
                    obj.aceEditor.setReadOnly(true);
                }
            });
        } else {
            _source = spawn('textarea', null);

            _editor = XNAT.app.codeEditor.init(_source, {
                language: 'json'
            });

            _editor.openEditor({
                title: 'Add New Command',
                classes: 'plugin-json',
                buttons: {
                    save: {
                        label: 'Save Command',
                        action: function(){
                            var editorContent = _editor.getValue().code;
                            // editorContent = JSON.stringify(editorContent).replace(/\r?\n|\r/g,' ');

                            XNAT.xhr.postJSON({
                                url: commandUrl(),
                                dataType: 'json',
                                data: editorContent,
                                success: function(obj){
                                    imageListManager.refreshTable();
                                    xmodal.close(obj.$modal);
                                    XNAT.ui.banner.top(2000, 'Command definition saved.', 'success');
                                },
                                fail: function(e){
                                    errorHandler(e, 'Could Not Save');
                                }
                            });
                        }
                    },
                    cancel: {
                        label: 'Cancel',
                        action: function(obj){
                            xmodal.close(obj.$modal);
                        }
                    }
                }
            });
        }
    };


    // create table for listing comands
    commandListManager.table = function(imageName,callback){

        // initialize the table - we'll add to it below
        var chTable = XNAT.table({
            className: 'enabled-commands xnat-table',
            style: {
                width: '100%',
                marginTop: '15px',
                marginBottom: '15px'
            }
        });

        // add table header row
        chTable.tr()
            .th({ addClass: 'left', html: '<b>Command</b>' })
            .th('<b>XNAT Actions</b>')
            .th('<b>Site-wide Config</b>')
            .th('<b>Version</b>')
            .th('<b>Actions</b>');

        function viewLink(item, text){
            return spawn('a.link|href=#!', {
                onclick: function(e){
                    e.preventDefault();
                    commandDefinition.dialog(item, false);
                }
            }, [['b', text]]);
        }

        function viewCommandButton(item){
            return spawn('button.btn.sm', {
                onclick: function(e){
                    e.preventDefault();
                    commandDefinition.dialog(item, false);
                }
            }, 'View Command');
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
                                }
                            });
                        }
                    })
                }
            }, 'Delete');
        }

        commandListManager.getAll(imageName).done(function(data) {
            if (data) {
                for (var i = 0, j = data.length; i < j; i++) {
                    var xnatActions = '', item = data[i];
                    if (item.xnat) {
                        for (var k = 0, l = item.xnat.length; k < l; k++) {
                            if (xnatActions.length > 0) xnatActions += '<br>';
                            xnatActions += item.xnat[k].description;
                            if (item.xnat[k].contexts.length > 0) {
                                var contexts = item.xnat[k].contexts;
                                xnatActions += "<ul>";
                                contexts.forEach(function(item){
                                    xnatActions +="<li>"+item+"</li>";
                                });
                                xnatActions += "</ul>";
                            }
                        }
                    } else {
                        xnatActions = 'N/A';
                    }
                    chTable.tr({title: item.name, data: {id: item.id, name: item.name, image: item.image}})
                        .td([viewLink(item, item.name)]).addClass('name')
                        .td(xnatActions)
                        .td('N/A')
                        .td(item.version)
                        .td([['div.center', [viewCommandButton(item), spacer(10), deleteCommandButton(item)]]]);
                }
            } else {
                // create a handler when no command data is returned.
                chTable.tr({title: 'No command data found'})
                    .td({colSpan: '5', html: 'No Commands Found'});
            }
        });

        commandListManager.$table = $(chTable.table);

        return chTable.table;
    };

    imageFilterManager.init = function(container){

        var $manager = $$(container||'div#image-filter-bar');
        var $footer = $('#image-filter-bar').parents('.panel').find('.panel-footer');

        imageFilterManager.container = $manager;

        var newImage = spawn('button.new-image.btn.btn-sm.submit', {
            html: 'Add New Image',
            onclick: function(){
                addImage.dialog(null);
            }
        });

        // add the 'add new' button to the panel footer
        $footer.append(spawn('div.pull-right', [
            newImage
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

    imageListManager.init = function(container){
        var $manager = $$(container||'div#image-list-container');

        var newCommand = spawn('button.new-command.btn.sm', {
            html: 'Add New Command',
            onclick: function(){
                commandDefinition.dialog(null,true); // opens the command code editor dialog with "new command" set to true
            }
        });

        function deleteImageButton(image) {
            return spawn('button.btn.sm',{
                html: 'Delete Image',
                onclick: function(){
                    xmodal.confirm({
                        height: 220,
                        scroll: false,
                        content: "" +
                        "<p>Are you sure you'd like to delete the "+image.tags[0]+" image?</p>" +
                        "<p><strong>This action cannot be undone.</strong></p>",
                        okAction: function(){
                            console.log('delete image id', image['image-id']);
                            XNAT.xhr.delete({
                                url: imageUrl(image['image-id']),
                                success: function(){
                                    console.log(image.tags[0] + ' image deleted');
                                    XNAT.ui.banner.top(1000, '<b>' + image.tags[0] + ' image deleted.', 'success');
                                    imageListManager.refreshTable();
                                },
                                fail: function(e){
                                    errorHandler(e);
                                }
                            })
                        }
                    })
                }
            });
        }

        imageListManager.container = $manager;

        imageListManager.getAll().done(function(data){
            if (data.length > 0) {
                for (var i=0, j=data.length; i<j; i++) {
                    var imageInfo = data[i];
                    $manager.append(spawn('div.imageContainer',[
                        ['h3.imageTitle',[imageInfo.tags[0], ['span.pull-right',[ deleteImageButton(imageInfo) ]]]],
                        ['div.imageCommandList',[commandListManager.table(imageInfo.tags[0])]],
                        ['div',[ newCommand ]]
                    ]));
                }
            } else {
                $manager.append(spawn('p',['There are no images installed in this XNAT.']));
            }

        });
    };


    imageListManager.refresh = imageListManager.refreshTable = function(container){
        container = $$(container || 'div#image-list-container');
        container.html('');
        imageListManager.init();
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

    /* duplicate - delete
    function commandUrl(appended){
        appended = isDefined(appended) ? appended : '';
        return rootUrl('/xapi/commands' + appended);
    }
    */

    function configUrl(command,wrapperName,appended){
        appended = isDefined(appended) ? '?' + appended : '';
        if (!command || !wrapperName) return false;
        return rootUrl('/xapi/commands/'+command+'/wrappers/'+wrapperName+'/config' + appended);
    }

    function configEnableUrl(command,wrapperName,flag){
        if (!command || !wrapperName || !flag) return false;
        return rootUrl('/xapi/commands/'+command+'/wrappers/'+wrapperName+'/' + flag);
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
        var chTable = XNAT.table({
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
            return '<input type="hidden" name="'+name+'" value="'+value+'" />'
        }


        // determine which type of table to build.
        if (config.type === 'inputs') {
            var inputs = config.inputs;

            // add table header row
            chTable.tr()
                .th({ addClass: 'left', html: '<b>Input</b>' })
                .th('<b>Default Value</b>')
                .th('<b>Matcher Value</b>')
                .th('<b>User-Settable?</b>')
                .th('<b>Advanced?</b>');

            for (i in inputs) {
                var input = inputs[i];
                chTable.tr({ data: { input: i }, className: 'input' })
                    .td( { data: { key: 'key' }, addClass: 'left'}, i )
                    .td( { data: { key: 'property', property: 'default-value' }}, basicConfigInput('defaultVal',input['default-value']) )
                    .td( { data: { key: 'property', property: 'matcher' }}, basicConfigInput('matcher',input['matcher']) )
                    .td( { data: { key: 'property', property: 'user-settable' }}, [['div', [configCheckbox('userSettable',input['user-settable']) ]]])
                    .td( { data: { key: 'property', property: 'advanced' }}, [['div', [configCheckbox('advanced',input['advanced']) ]]]);

            }

        } else if (config.type === 'outputs') {
            var outputs = config.outputs;

            // add table header row
            chTable.tr()
                .th({ addClass: 'left', html: '<b>Output</b>' })
                .th({ addClass: 'left', width: '75%', html: '<b>Label</b>' });

            for (o in outputs) {
                var output = outputs[o];
                chTable.tr({ data: { output: o }, className: 'output' })
                    .td( { data: { key: 'key' }, addClass: 'left'}, o )
                    .td( { data: { key: 'property', property: 'label' }}, basicConfigInput('label',output['label']) );
            }

        }

        configDefinition.$table = $(chTable.table);

        return chTable.table;
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

                xmodal.open({
                    title: 'Set Config Values',
                    template: tmpl.clone(),
                    width: 850,
                    height: 500,
                    scroll: true,
                    beforeShow: function(obj){
                        var $panel = obj.$modal.find('#config-viewer-panel');
                        /*
                         $panel.find('input[type=text]').each(function(){
                         $(this).val($(this).data('value'));
                         });
                         */
                        $panel.find('input[type=checkbox]').each(function(){
                            $(this).prop('checked',$(this).data('checked'));
                        })
                    },
                    okClose: false,
                    okLabel: 'Save',
                    okAction: function(obj){
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
                                xmodal.closeAll();
                            },
                            fail: function(e){ errorHandler(e); }
                        });
                    }
                });

            })
            .fail(function(e){
                errorHandler(e, 'Could Not Open Config Definition');
            });

    };


    commandConfigManager.table = function(callback){

        // initialize the table - we'll add to it below
        var chTable = XNAT.table({
            className: 'sitewide-command-configs xnat-table',
            style: {
                width: '100%',
                marginTop: '15px',
                marginBottom: '15px'
            }
        });

        // add table header row
        chTable.tr()
            .th({ addClass: 'left', html: '<b>XNAT Command Label</b>' })
            .th('<b>Container</b>')
            .th('<b>Enabled</b>')
            .th('<b>Actions</b>');

        function viewLink(item, wrapper, text){
            return spawn('a.link|href=#!', {
                onclick: function(e){
                    e.preventDefault();
                    configDefinition.dialog(item.id, wrapper.name, false);
                    console.log('Open Config definition for '+ wrapper.name);
                }
            }, [['b', text]]);
        }

        function viewConfigButton(item,wrapper){
            return spawn('button.btn.sm', {
                onclick: function(e){
                    e.preventDefault();
                    configDefinition.dialog(item.id, wrapper.name, false);
                }
            }, 'View Command Configuration');
        }

        function enabledCheckbox(item,wrapper){
            var enabled = !!item.enabled;
            var ckbox = spawn('input.config-enabled', {
                type: 'checkbox',
                checked: enabled,
                value: enabled,
                data: { name: item.name },
                onchange: function(){
                    // save the status when clicked
                    var checkbox = this;
                    enabled = checkbox.checked;
                    var enabledFlag = (enabled) ? 'enabled' : 'disabled';

                    XNAT.xhr.put({
                        url: configEnableUrl(item.id,wrapper.name,enabledFlag),
                        success: function(){
                            var status = (enabled ? ' enabled' : ' disabled');
                            checkbox.value = enabled;
                            XNAT.ui.banner.top(1000, '<b>' + wrapper.name+ '</b> ' + status, 'success');
                            console.log(wrapper.name + status)
                        },
                        fail: function(e){
                            errorHandler(e);
                        }
                    });
                }
            });

            return spawn('div.center', [
                spawn('label.switchbox|title=' + item.name, [
                    ckbox,
                    ['span.switchbox-outer', [['span.switchbox-inner']]]
                ])
            ]);

        }

        function deleteConfigButton(item,wrapper){
            return spawn('button.btn.sm.delete', {
                onclick: function(){
                    xmodal.confirm({
                        height: 220,
                        scroll: false,
                        content: "" +
                        "<p>Are you sure you'd like to delete the <b>" + item.name + "</b> command configuration?</p>" +
                        "<p><b>This action cannot be undone. This action does not delete any project-specific configurations for this command.</b></p>",
                        okAction: function(){
                            console.log('delete id ' + item.id);
                            XNAT.xhr.delete({
                                url: configUrl(item.id,wrapper.name),
                                success: function(){
                                    console.log('"'+ wrapper.name + '" command deleted');
                                    XNAT.ui.banner.top(1000, '<b>"'+ wrapper.name + '"</b> configuration deleted.', 'success');
                                    commandConfigManager.refreshTable();
                                },
                                fail: function(e){
                                    errorHandler(e);
                                }
                            });
                        }
                    })
                }
            }, 'Delete');
        }

        commandConfigManager.getAll().done(function(data) {
            if (data) {
                for (var i = 0, j = data.length; i < j; i++) {
                    var item = data[i];
                    if (item.xnat) {
                        for (var k = 0, l = item.xnat.length; k < l; k++) {
                            var wrapper = item.xnat[k];

                            XNAT.xhr.get({
                                url: configEnableUrl(item.id,wrapper.name,'enabled'),
                                success: function(enabled){
                                    item.enabled = enabled;
                                    chTable.tr({title: wrapper.name, data: {id: item.id, name: wrapper.name, image: item.image}})
                                        .td([viewLink(item, wrapper, wrapper.description)]).addClass('name')
                                        .td(item.image)
                                        .td([['div.center', [enabledCheckbox(item,wrapper)]]])
                                        .td([['div.center', [viewConfigButton(item,wrapper), spacer(10), deleteConfigButton(item,wrapper)]]]);
                                },
                                fail: function(e){
                                    errorHandler(e);
                                }
                            });
                        }
                    }
                }
            } else {
                // create a handler when no command data is returned.
                chTable.tr({title: 'No command config data found'})
                    .td({colSpan: '5', html: 'No XNAT-enabled Commands Found'});
            }
        });

        commandConfigManager.$table = $(chTable.table);

        return chTable.table;
    };
    
    commandConfigManager.refresh = commandConfigManager.refreshTable = function(container){
        var $manager = $$(container||'div#command-config-list-container');

        $manager.html('');
        $manager.append(commandConfigManager.table());
    };

    commandConfigManager.init = function(container){
        var $manager = $$(container||'div#command-config-list-container');

        commandConfigManager.container = $manager;

        $manager.append(commandConfigManager.table());
    };

    commandConfigManager.init();
    
    
    
    
/* =============== *
 * Command History *
 * =============== */

    console.log('commandHistory.js');

    var historyTable;

    XNAT.plugin.containerService.historyTable = historyTable =
        getObject(XNAT.plugin.containerService || {});

    function getCommandHistoryUrl(appended){
        appended = (appended) ? '?'+appended : '';
        return rootUrl('/xapi/containers' + appended);
    }

    historyTable.table = function(callback){
        // initialize the table - we'll add to it below
        var chTable = XNAT.table({
            className: 'sitewide-command-configs xnat-table compact',
            style: {
                width: '100%',
                marginTop: '15px',
                marginBottom: '15px'
            }
        });

        // add table header row
        chTable.tr()
            .th({ addClass: 'left', html: '<b>ID</b>' })
            .th('<b>Image</b>')
            .th('<b>Command</b>')
            .th('<b>User</b>')
            .th('<b>Date</b>')
            .th('<b>Input</b>')
            .th('<b>Output</b>');

        function displayDate(timestamp){
            var d = new Date(timestamp);
            return d.toISOString().replace('T',' ').replace('Z',' ');
        }

        function displayInput(inputObj){
            for (var i in inputObj){
                if (i=="scan") {
                    var sessionId = inputObj[i].split('/')[2];
                    var scanId = inputObj[i].split('/scans/')[1];
                    return spawn(['a|href='+rootUrl('/data/experiments/'+sessionId), sessionId+': '+scanId ]);
                }
            }
        }

        function displayOutput(outputArray){
            var o = outputArray[0];
            return o.label;
        }

        XNAT.xhr.getJSON({
            url: getCommandHistoryUrl(),
            fail: function(e){
                errorHandler(e);
            },
            success: function(data){
                if (data.length > 0) {
                    data.forEach(function(item){
                        chTable.tr({title: item['container-id'], id: item['container-id'] })
                            .td({ addClass: 'left', html: '<b>'+item['id']+'</b>' })
                            .td(item['docker-image'])
                            .td('dcm2niix-scan')
                            .td(item['user-id'])
                            .td([ displayDate(item['timestamp']) ])
                            .td([ displayInput(item['rawInputs']) ])
                            .td([ displayOutput(item['outputs']) ]);
                    })
                } else {
                    chTable.tr()
                        .td({ colspan: 7, html: "No history entries found" });
                }

            }
        });

        historyTable.$table = $(chTable.table);

        return chTable.table;
    };

    historyTable.init = function(container){
        var manager = $$(container || '#command-history-container');
        manager.html('');

        manager.append(historyTable.table());
    };

    historyTable.init();

}));