XNAT = getObject(XNAT || {});

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
}(function() {

    /* ================== *
     * Command Automation *
     * ================== */

    console.log('commandAutomation.js');

    const restUrl = XNAT.url.restUrl;
    let commandAutomation;

    XNAT.plugin.containerService.commandAutomation = commandAutomation =
        getObject(XNAT.plugin.containerService.commandAutomation || {});

    function getCommandAutomationUrl(appended){
        appended = (appended) ? '?'+appended : '';
        return restUrl('/xapi/commandeventmapping' + appended);
    }

    function commandAutomationIdUrl(id){
        return restUrl('/xapi/commandeventmapping/' + id );
    }

    function commandAutomationConvertUrl(id){
        return restUrl('/xapi/commandeventmapping/' + id  + '/convert');
    }

    $(document).on('click', '.deleteAutomationButton', function(){
        const automationID = $(this).data('id');
        if (automationID) {
            XNAT.xhr.delete({
                url: commandAutomationIdUrl(automationID),
                success: function(){
                    XNAT.ui.banner.top(2000,'Successfully removed command automation from project.','success');
                    XNAT.plugin.containerService.commandAutomation.refresh();
                },
                fail: function(e){
                    XNAT.plugin.containerService.errorHandler(e, 'Could not delete command automation');
                }
            })
        }
    });

    $(document).on('click', '.convertAutomationButton', function(){
        const automationID = $(this).data('id');
        if (automationID) {
            XNAT.ui.dialog.confirm({
                title: "Confirm conversion to Event Service Subscription",
                content: "<p>Converting a Command Event Mapping to an Event Service Subscription will retain all " +
                    "functionality. You will need to <strong>review and enable</strong> the Event Service Subscription " +
                    "after the conversion completes.</p><p>Proceed with conversion?</p>",
                okLabel: "Yes",
                okAction: function () {
                    XNAT.xhr.post({
                        url: commandAutomationConvertUrl(automationID),
                        success: function(){
                            XNAT.ui.dialog.confirm({
                                title: "Converted successfully",
                                content: "<p>Command Event Mapping was successfully converted to an Event Service Subscription, " +
                                    "which is currently <strong>disabled</strong> pending review. The subscription will " +
                                    "not run until it is enabled; please visit the Event Service configuration page " +
                                    "to review and enable.</p>",
                                okLabel: "Visit Event Service config",
                                okAction: function(){
                                    window.location.assign(XNAT.url.rootUrl('/app/template/Page.vm?view=admin/event-service#tab=event-setup-tab'));
                                },
                                cancelLabel: "Not now",
                                cancelAction: XNAT.plugin.containerService.commandAutomation.refresh
                            });
                        },
                        fail: function(e){
                            XNAT.plugin.containerService.errorHandler(e, 'Could not convert command automation');
                        }
                    });
                },
            });
        }
    });

    commandAutomation.table = function(isAdmin){
        // if the user has admin privileges, then display additional controls.
        isAdmin = isAdmin || false;
        const project = XNAT.plugin.containerService.getProjectId();

        // initialize the table - we'll add to it below
        const caTable = XNAT.table({
            className: 'xnat-table compact',
            style: {
                width: '100%',
                marginTop: '15px',
                marginBottom: '15px'
            }
        });

        // add table header row
        caTable.tr()
            .th({ addClass: 'left', html: '<b>ID</b>' });
        if (!project) {
            caTable.th('<b>Project</b>');
        }
        caTable.th('<b>Event</b>')
            .th('<b>Command</b>')
            .th('<b>Created By</b>')
            .th('<b>Date Created</b>')
            .th('<b>Enabled</b>')
            .th('<b>Action</b>');

        function displayDate(timestamp){
            const d = new Date(timestamp);
            return d.toISOString().replace('T',' ').replace('Z',' ');
        }

        function deleteAutomationButton(id, isAdmin){
            if (isAdmin) return spawn('button.deleteAutomationButton', {
                data: {id: id},
                title: 'Delete Automation'
            }, [ spawn ('i.fa.fa-trash') ]);
        }

        function convertAutomationButton(id, isAdmin){
            if (isAdmin) return spawn('button.convertAutomationButton', {
                data: {id: id},
                title: 'Convert to Event Service Subscription'
            }, [ spawn ('i.fa.fa-exchange') ]);
        }

        function spacer(width){
            return spawn('i.spacer', {
                style: {
                    display: 'inline-block',
                    width: width + 'px'
                }
            });
        }

        XNAT.xhr.getJSON({
            url: getCommandAutomationUrl(),
            fail: function(e){
                XNAT.plugin.containerService.errorHandler(e);
            },
            success: function(data) {
                // data returns an array of known command event mappings
                if (project) {
                    data = data.filter(mapping => mapping['project'] === project);
                }

                if (!data.length) {
                    const colspan = project ? '7' : '8';
                    caTable.tr()
                        .td({ colSpan: colspan, html: 'No command event mappings exist.' });
                    return;
                }

                data.forEach(function(mapping) {
                    caTable.tr()
                        .td( '<b>'+mapping['id']+'</b>' );

                    if (!project) {
                        caTable.td(mapping['project']);
                    }

                    caTable
                        .td( mapping['event-type'] )
                        .td( mapping['xnat-command-wrapper'] )
                        .td( mapping['subscription-user-name'] )
                        .td( displayDate(mapping['timestamp']) )
                        .td( mapping['enabled'] )
                        .td([spawn('div.center', [
                                convertAutomationButton(mapping['id'], isAdmin),
                                spacer(2),
                                deleteAutomationButton(mapping['id'], isAdmin)
                        ])])
                });
            }
        });

        commandAutomation.$table = $(caTable.table);

        return caTable.table;
    };

    commandAutomation.init = commandAutomation.refresh = function(){
        // initialize the list of command automations
        const manager = $('#command-automation-list');
        const $footer = manager.parents('.panel').find('.panel-footer');

        manager.html('');
        $footer.html('');

        manager.append(commandAutomation.table(XNAT.plugin.containerService.isAdmin));
    };
}));