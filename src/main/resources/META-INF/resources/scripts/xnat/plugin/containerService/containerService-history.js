/*
 * web: containerServices-history.js
 * XNAT http://www.xnat.org
 * Copyright (c) 2005-2017, Washington University School of Medicine and Howard Hughes Medical Institute
 * All Rights Reserved
 *
 * Released under the Simplified BSD.
 */

/*!
 * History Table Generator for Container Services
 */

console.log('containerService-history.js');

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
}(function() {

    /* ================ *
     * GLOBAL FUNCTIONS *
     * ================ */

    var undefined,
        rootUrl = XNAT.url.rootUrl,
        restUrl = XNAT.url.restUrl,
        csrfUrl = XNAT.url.csrfUrl;

    function spacer(width) {
        return spawn('i.spacer', {
            style: {
                display: 'inline-block',
                width: width + 'px'
            }
        })
    }

    function errorHandler(e, title, closeAll) {
        console.log(e);
        title = (title) ? 'Error: ' + title : 'Error';
        closeAll = (closeAll === undefined) ? true : closeAll;
        var errormsg = (e.statusText) ? '<p><strong>Error ' + e.status + ': ' + e.statusText + '</strong></p><p>' + e.responseText + '</p>' : e;
        XNAT.dialog.open({
            width: 450,
            title: title,
            content: errormsg,
            buttons: [
                {
                    label: 'OK',
                    isDefault: true,
                    close: true,
                    action: function () {
                        if (closeAll) {
                            xmodal.closeAll();
                            XNAT.ui.dialog.closeAll();
                        }
                    }
                }
            ]
        });
    }

    /* =============== *
     * Command History *
     * =============== */

    var historyTable, containerHistory, wrapperList;

    XNAT.plugin.containerService.historyTable = historyTable =
        getObject(XNAT.plugin.containerService.historyTable || {});

    XNAT.plugin.containerService.containerHistory = containerHistory =
        getObject(XNAT.plugin.containerService.containerHistory || {});

    const historyTableContainerId = 'command-history-container';

    function viewHistoryDialog(e, onclose) {
        e.preventDefault();
        var historyId = $(this).data('id') || $(this).closest('tr').prop('title');
        XNAT.plugin.containerService.historyTable.viewHistory(historyId);
    }

    const labelMap = {
        // id: {label: 'ID', show: true, op: 'eq', type: 'number'},
        command: {label: 'Command', column: 'commandLine', show: true},
        user: {label: 'User', column: 'userId', show: true},
        DATE: {label: 'Date', column: 'statusTime', show: true},
        ROOTELEMENT: {label: 'Root element', show: true},
        status: {label: 'Status', show: true},
    };

    function historyTableObject() {
        return {
            table: {
                classes: "compact fixed-header selectable scrollable-table",
                style: "width: auto;",
                on: [
                    ['click', 'a.view-container-history', viewHistoryDialog]
                ]
            },
            before: {
                filterCss: {
                    tag: 'style|type=text/css',
                    content:
                        '#' + historyTableContainerId + ' .DATE { width: 160px !important; } \n' +
                        '#' + historyTableContainerId + ' .command { width: 210px !important; }  \n' +
                        '#' + historyTableContainerId + ' .user { width: 120px !important; }  \n' +
                        '#' + historyTableContainerId + ' .ROOTELEMENT {width: 180px !important; }' +
                        '#' + historyTableContainerId + ' .status { width: 130px !important; }  \n'
                }
            },
            sortable: 'user, DATE, status',
            filter: 'user, status, command',
            items: {
                // id: {
                //     th: {className: 'id'},
                //     label: labelMap.id['label'],
                //     apply: function(){
                //         return this.id.toString();
                //     }
                // },
                DATE: {
                    label: labelMap.DATE['label'],
                    th: {className: 'DATE'},
                    apply: function () {
                        let time = this['status-time'];
                        return time ? new Date(time).toLocaleString() : 'N/A';
                    }
                },
                command: {
                    th: {className: 'command'},
                    td: {className: 'command word-wrapped'},
                    label: labelMap.command['label'],
                    apply: function () {
                        var label, wrapper;
                        if (wrapperList && wrapperList.hasOwnProperty(this['wrapper-id'])) {
                            wrapper = wrapperList[this['wrapper-id']];
                            label = (wrapper.description) ?
                                wrapper.description :
                                wrapper.name;
                        } else {
                            label = this['command-line'];
                        }

                        return spawn('a.view-container-history', {
                            href: '#!',
                            title: 'View command history and logs',
                            data: {'id': this.id},
                            style: { wordWrap: 'break-word' },
                            html: label
                        });
                    }
                },
                user: {
                    th: {className: 'user'},
                    td: {className: 'user word-wrapped'},
                    label: labelMap.user['label'],
                    apply: function () {
                        return this['user-id']
                    }
                },
                ROOTELEMENT: {
                    th: {className: 'ROOTELEMENT'},
                    td: {className: 'ROOTELEMENT word-wrapped'},
                    label: labelMap.ROOTELEMENT['label'],
                    apply: function(){
                        var rootElements = this.inputs.filter(function(input){ if (input.type === "wrapper-external") return input });
                        if (rootElements.length) {
                            var elementsToDisplay = [];
                            rootElements.forEach(function(element){
                                var label = (element.value.indexOf('scans') >= 0) ?
                                    'session: ' + element.value.split('/')[3] + ' <br>scan: ' + element.value.split('/')[element.value.split('/').length-1] :
                                    element.name + ': ' + element.value.split('/')[element.value.split('/').length-1];

                                var link = (element.value.indexOf('scans') >= 0) ?
                                    element.value.split('/scans')[0] :
                                    element.value;

                                elementsToDisplay.push(
                                    spawn('a.root-element', {
                                        href: XNAT.url.rootUrl('/data/'+link+'?format=html'),
                                        html: label
                                    })
                                );
                            });

                            return spawn('!',elementsToDisplay)
                        }
                        else {
                            return 'Unknown';
                        }
                    }
                },
                status: {
                    th: {className: 'status'},
                    td: {className: 'status word-wrapped'},
                    label: labelMap.status['label'],
                    apply: function(){
                        return this['status'];
                    }
                }
            }
        }
    }

    historyTable.workflowModal = function(workflowIdOrEvent) {
        var workflowId;
        if (workflowIdOrEvent.hasOwnProperty("data")) {
            // this is an event
            workflowId = workflowIdOrEvent.data.wfid;
        } else {
            workflowId = workflowIdOrEvent;
        }
        // rptModal in xdat.js
        rptModal.call(this, workflowId, "wrk:workflowData", "wrk:workflowData.wrk_workflowData_id");
    };

    var containerModalId = function(containerId, logFile) {
        return 'container-'+containerId+'-log-'+logFile;
    };

    var checkContinueLiveLog = function(containerId, logFile, refreshLogSince, bytesRead) {
        // This will stop making ajax requests until the user clicks "continue"
        // thus allowing the session timeout to handle an expiring session
        XNAT.dialog.open({
            width: 360,
            content: '' +
                '<div style="font-size:14px;">' +
                'Are you still watching this log?' +
                '<br><br>'+
                'Click <b>"Continue"</b> to continue tailing the log ' +
                'or <b>"Close"</b> to close it.' +
                '</div>',
            buttons: [
                {
                    label: 'Close',
                    close: true,
                    action: function(){
                        XNAT.dialog.closeAll();
                    }
                },
                {
                    label: 'Continue',
                    isDefault: true,
                    close: true,
                    action: function(){
                        refreshLog(containerId, logFile, refreshLogSince, bytesRead);
                    }
                }
            ]
        });
    };

    historyTable.$loadAllBtn = false;
    historyTable.refreshLog = refreshLog = function(containerId, logFile, refreshLogSince, bytesRead, loadAll, startTime) {
        var fullWait;
        var refreshPrm = {};
        if (refreshLogSince) refreshPrm.since = refreshLogSince;
        if (bytesRead) refreshPrm.bytesRead = bytesRead;
        if (loadAll) {
            fullWait = XNAT.ui.dialog.static.wait('Fetching log, please wait.');
            refreshPrm.loadAll = loadAll;
        }

        var firstRun = $.isEmptyObject(refreshPrm);

        if (!startTime) {
            startTime = new Date();
        } else {
            // Check uptime
            var maxUptime = 900; //sec
            var uptime = Math.round((new Date() - startTime)/1000);
            if (uptime >= maxUptime) {
                checkContinueLiveLog(containerId, logFile, refreshLogSince, bytesRead);
                return;
            }
        }

        var $container = historyTable.logModal.content$.parent();

        // Functions for adding log content to modal
        function appendContent(content, clear) {
            if (firstRun || clear) historyTable.logModal.content$.empty();
            var lines = content.split('\n').filter(function(line){return line;}); // remove empty lines
            if (lines.length > 0) {
                historyTable.logModal.content$.append(spawn('pre',
                    {'style': {'font-size':'12px','margin':'0', 'white-space':'pre-wrap'}}, lines.join('<br/>')));
            }
        }

        function addLiveLoggingContent(dataJson) {
            // We're live logging
            var currentScrollPos = $container.scrollTop(),
                containerHeight = $container[0].scrollHeight,
                autoScroll = $container.height() + currentScrollPos >= containerHeight; //user has not scrolled

            //append content
            appendContent(dataJson.content);

            //scroll to bottom
            if (autoScroll) $container.scrollTop($container[0].scrollHeight);

            if (dataJson.timestamp !== "-1") {
                // Container is still running, check for more!
                refreshLog(containerId, logFile, dataJson.timestamp, false, false, startTime);
            }
        }

        function removeLoadAllBtn() {
            if (historyTable.$loadAllBtn) {
                historyTable.$loadAllBtn.remove();
                historyTable.$loadAllBtn = false;
            }
        }

        function addLoadAllBtn(curBytesRead) {
            removeLoadAllBtn();
            historyTable.$loadAllBtn = $('<button class="button btn" id="load-log">Load entire log file</button>');
            historyTable.$loadAllBtn.appendTo(historyTable.logModal.footerButtons$);
            historyTable.$loadAllBtn.click(function(){
                $container.off("scroll");
                $container.scrollTop($container[0].scrollHeight);
                refreshLog(containerId, logFile, false, curBytesRead, true);
            });
        }

        function startScrolling(curBytesRead) {
            $container.scroll(function() {
                if ($(this).scrollTop() + $(this).innerHeight() >= $(this)[0].scrollHeight) {
                    $container.off("scroll");
                    addLoadAllBtn(curBytesRead);
                    refreshLog(containerId, logFile, false, curBytesRead);
                }
            });
        }

        function addFileContent(dataJson, clear) {
            appendContent(dataJson.content, clear);
            if (dataJson.bytesRead === -1) {
                // File read in its entirety
                removeLoadAllBtn();
            } else {
                startScrolling(dataJson.bytesRead);
            }
        }

        var $waitElement = $('<span class="spinner text-info"><i class="fa fa-spinner fa-spin"></i> Loading...</span>');
        XNAT.xhr.getJSON({
            url: rootUrl('/xapi/containers/' + containerId + '/logSince/' + logFile),
            data: refreshPrm,
            beforeSend: function () {
                if (firstRun || bytesRead) $waitElement.appendTo(historyTable.logModal.content$);
            },
            success: function (dataJson) {
                if (firstRun || bytesRead) $waitElement.remove();
                if (fullWait) {
                    fullWait.close();
                }

                // Ensure that user didn't close modal
                if ($container.length === 0 || $container.is(':hidden')) {
                    return;
                }

                var fromFile = dataJson.fromFile;
                if (fromFile) {
                    // file content
                    var emptyFirst = false;
                    if (firstRun) {
                        historyTable.logModal.title$.text(historyTable.logModal.title$.text() + ' (from file)');
                    } else if (refreshLogSince) {
                        // We were live logging, but we swapped to reading a file, notify user since we're starting back from the top
                        XNAT.ui.dialog.alert('Processing completed');
                        historyTable.logModal.title$.text(
                            historyTable.logModal.title$.text().replace('(live)', '(from file)')
                        );
                        emptyFirst = true;
                    }
                    addFileContent(dataJson, emptyFirst);
                } else {
                    // live logging content
                    if (firstRun) {
                        historyTable.logModal.title$.text(historyTable.logModal.title$.text() + ' (live)');
                    }
                    addLiveLoggingContent(dataJson);
                }
            },
            error: function (e) {
                if (e.status === 403) {
                    errorHandler(e, 'Insufficient permissions to retrieve ' + logFile , true);
                } else {
                    errorHandler(e, 'Cannot retrieve ' + logFile + '; container may have restarted.', true);
                }
            }
        });
    };

    historyTable.viewLog = viewLog = function (containerId, logFile) {
        historyTable.logModal = XNAT.dialog.open({
            title: 'View ' + logFile,
            id: containerModalId(containerId, logFile),
            width: 850,
            header: true,
            maxBtn: true,
            beforeShow: function() {
                refreshLog(containerId, logFile);
            },
            buttons: [
                {
                    label: 'Done',
                    isDefault: true,
                    close: true
                }
            ]
        });
    };

    historyTable.viewHistoryEntry = function(historyEntry) {
        var historyDialogButtons = [
            {
                label: 'Done',
                isDefault: true,
                close: true
            }
        ];

        // build nice-looking history entry table
        var pheTable = XNAT.table({
            className: 'xnat-table compact',
            style: {
                width: '100%',
                marginTop: '15px',
                marginBottom: '15px'
            }
        });

        var allTables = [spawn('h3', 'Container information'), pheTable.table];

        for (var key in historyEntry) {
            var val = historyEntry[key], formattedVal = '', putInTable = true;

            if (Array.isArray(val) && val.length > 0) {
                // Display a table
                var columns = [];
                val.forEach(function (item) {
                    if (typeof item === 'object') {
                        Object.keys(item).forEach(function(itemKey){
                            if(columns.indexOf(itemKey)===-1){
                                columns.push(itemKey);
                            }
                        });
                    }
                });


                formattedVal="<table class='xnat-table'>";
                if (columns.length > 0) {
                    formattedVal+="<tr>";
                    columns.forEach(function(colName){
                        formattedVal+="<th>"+colName+"</th>";
                    });
                    formattedVal+="</tr>";

                    val.sort(function(obj1,obj2){
                        // Sort by time recorded (if we have it)
                        var date1 = Date.parse(obj1["time-recorded"]), date2 = Date.parse(obj2["time-recorded"]);
                        return date1 - date2;
                    });
                } else {
                    // skip header if we just have one column
                    // sort alphabetically
                    val.sort()
                }

                val.forEach(function (item) {
                	formattedVal+="<tr>";
                    if (typeof item === 'object') {
                        columns.forEach(function (itemKey) {
                            formattedVal += "<td nowrap>";
                            var temp = item[itemKey];
                            if (typeof temp === 'object') temp = JSON.stringify(temp);
                            formattedVal += temp;
                            formattedVal += "</td>";
                        });
                    } else {
                        formattedVal += "<td nowrap>";
                        formattedVal += item;
                        formattedVal += "</td>";
                    }
                    formattedVal+="</tr>";
                });
                formattedVal+="</table>";
                putInTable = false;
            } else if (typeof val === 'object') {
                formattedVal = spawn('code', JSON.stringify(val));
            } else if (!val) {
                formattedVal = spawn('code', 'false');
            } else if (key === 'workflow-id') {
                // Allow pulling up detailed workflow info (can contain addl info in details field)
                // var curid = '#wfmodal' + val;
                formattedVal = spawn('a.wfmodal', { data: { 'wfid':val }}, val);
            } else {
                formattedVal = spawn('code', val);
            }

            if (putInTable) {
                pheTable.tr()
                    .td('<b>' + key + '</b>')
                    .td([spawn('div', {style: {'word-break': 'break-all', 'max-width': '600px', 'overflow':'auto'}}, formattedVal)]);
            } else {
                allTables.push(
                    spawn('div', {style: {'word-break': 'break-all', 'overflow':'auto', 'margin-bottom': '10px', 'max-width': 'max-content'}},
                        [spawn('div.data-table-actionsrow', {}, spawn('strong', {class: "textlink-sm data-table-action"},
                            'Container ' + key)), formattedVal])
                );
            }

            // check logs and populate buttons at bottom of modal
            if (key === 'log-paths') {
                if (historyEntry.backend.toLowerCase() !== 'kubernetes') {
                    historyDialogButtons.push({
                        label: 'View StdOut.log',
                        close: false,
                        action: function(){
                            historyTable.viewLog(historyEntry.id, 'stdout')
                        }
                    });

                    historyDialogButtons.push({
                        label: 'View StdErr.log',
                        close: false,
                        action: function(){
                            historyTable.viewLog(historyEntry.id, 'stderr')
                        }
                    });
                }
                else {
                    // Container executions in Kubernetes do not publish a StdErr log
                    historyDialogButtons.push({
                        label: 'View Logs',
                        close: false,
                        action: function(){
                            historyTable.viewLog(historyEntry.id, 'stdout')
                        }
                    });
                }
            }
            if (key === 'setup-container-id') {
                historyDialogButtons.push({
                    label: 'View Setup Container',
                    close: true,
                    action: function () {
                        historyTable.viewHistory(historyEntry[key]);
                    }
                })
            }
            if (key === 'parent-database-id' && historyEntry[key]) {
                var parentId = historyEntry[key];
                historyDialogButtons.push({
                    label: 'View Parent Container',
                    close: true,
                    action: function () {
                        historyTable.viewHistory(parentId);
                    }
                })
            }
        }

        // display history
        XNAT.ui.dialog.open({
            title: historyEntry['wrapper-name'],
            width: 800,
            scroll: true,
            content: spawn('div', allTables),
            buttons: historyDialogButtons,
            header: true,
            maxBtn: true
        });
    };

    historyTable.viewHistory = function (id) {
        if (XNAT.plugin.containerService.containerHistory.hasOwnProperty(id)) {
            historyTable.viewHistoryEntry(XNAT.plugin.containerService.containerHistory[id]);
        } else {
            console.log(id);
            XNAT.ui.dialog.open({
                content: 'Sorry, could not display this history item.',
                buttons: [
                    {
                        label: 'OK',
                        isDefault: true,
                        close: true
                    }
                ]
            });
        }
    };


    historyTable.findById = function(e){
        e.preventDefault();

        var id = $('#container-id-entry').val();
        if (!id) return false;

        XNAT.xhr.getJSON({
            url: restUrl('/xapi/containers/'+id),
            error: function(e){
                console.warn(e);
                XNAT.ui.dialog.message('Please enter a valid container ID');
                $('#container-id-entry').focus();
            },
            success: function(data){
                $('#container-id-entry').val('');
                historyTable.viewHistoryEntry(data);
            }
        })
    };

    $(document).off('keyup').on('keyup','#container-id-entry',function(e){
        var val = this.value;
        if (e.key === 'Enter' || e.keyCode === '13') {
            historyTable.findById(e);
        }
    });

    // open workflow entry dialog on click of the workflow ID in the standard history dialog
    $(document).off('click','.wfmodal').on('click', '.wfmodal', function(){
        let wfid = $(this).data('wfid');
        historyTable.workflowModal(wfid);
    } );

    historyTable.init = historyTable.refresh = function (context) {
        if (context) {
            historyTable.context = context;
        }
        function setupParams() {
            if (context) {
                XNAT.ui.ajaxTable.filters = XNAT.ui.ajaxTable.filters || {};
                XNAT.ui.ajaxTable.filters['project'] = {operator: 'eq', value: context, backend: 'hibernate'};
            }
        }

        wrapperList = getObject(XNAT.plugin.containerService.wrapperList || {});

        $('#' + historyTableContainerId).empty();
        XNAT.plugin.containerService.historyTable.commandHistory = XNAT.ui.ajaxTable.AjaxTable(XNAT.url.restUrl('/xapi/containers'),
            'container-history-table', historyTableContainerId, 'History', 'All containers',
            historyTableObject(), setupParams, null, dataLoadCallback, null, labelMap);

        XNAT.plugin.containerService.historyTable.commandHistory.load();

        // add a "find by ID" input field after the table renders
        var target = $('#'+historyTableContainerId),
            searchHistoryInput = spawn('input#container-id-entry', {
                type:'text',
                name: 'findbyid',
                placeholder: 'Find By ID',
                size: 12,
                style: {'font-size':'12px' }}
            ),
            searchHistoryButton = spawn(
                'button.btn2.btn-sm',
                {
                    title: 'Find By ID',
                    onclick: XNAT.plugin.containerService.historyTable.findById
                },
                [ spawn('i.fa.fa-search') ]);
        target.prepend(spawn('div.pull-right',[
            searchHistoryInput,
            spacer(4),
            searchHistoryButton
        ]));

    };

    function dataLoadCallback(data) {
        data.forEach(function (historyEntry) {
            // data.filter(function(entry){ return entry.id === historyEntry.id })[0].context = historyTable.context;
            historyEntry.context = historyTable.context;
            containerHistory[historyEntry.id] = historyEntry;
        });
    }
}));
