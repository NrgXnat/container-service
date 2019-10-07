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

    function getCommandHistoryUrl(appended) {
        appended = (appended) ? '?' + appended : '';
        return restUrl('/xapi/containers' + appended);
    }
    function getProjectHistoryUrl(projectId, appended) {
        appended = (appended) ? '?' + appended : '';
        return restUrl('/xapi/projects/'+projectId+'/containers'+appended);
    }

    function viewHistoryDialog(e, onclose) {
        e.preventDefault();
        var historyId = $(this).data('id') || $(this).closest('tr').prop('title');
        XNAT.plugin.containerService.historyTable.viewHistory(historyId);
    }

    function sortHistoryData(context) {

        var URL = (context === 'site') ?
            getCommandHistoryUrl() :
            getProjectHistoryUrl(context);

        return XNAT.xhr.getJSON(URL)
            .success(function (data) {

                if (data.length) {
                    // sort data by ID.
                    data = data.sort(function (a, b) {
                        return (a.id > b.id) ? 1 : -1
                    });

                    // add a project field before returning. For setup containers, this requires some additional work.
                    var setupContainers = data.filter(function (a) {
                        return (a.subtype) ? a.subtype.toLowerCase() === 'setup' : false
                    });
                    setupContainers.forEach(function (entry) {
                        var projectId = getProjectIdFromMounts(entry);
                        data[entry.id - 1].project = projectId;

                        if (entry['parent-database-id']) {
                            data[entry['parent-database-id'] - 1].project = projectId;
                            data[entry['parent-database-id'] - 1]['setup-container-id'] = entry.id;
                        }
                    });

                    // copy the history listing into an object for individual reference. Add the context value.
                    data.forEach(function (historyEntry) {
                        // data.filter(function(entry){ return entry.id === historyEntry.id })[0].context = historyTable.context;
                        historyEntry.context = historyTable.context;
                        containerHistory[historyEntry.id] = historyEntry;
                    });

                    return data;
                }
            })
    }

    function getProjectIdFromMounts(entry) {
        var mounts = entry.mounts;
        // assume that the first mount of a container is an input from a project. Parse the URI for that mount and return the project ID.
        //This does not work all the time - so traverse all the array elements to get the host-path
        var	mLen = mounts.length;
        var i,found = 0;
        var project = "Unknown";
        if (mLen) {
            for (i = 0; i < mLen; i++) {
                var inputMount = mounts[i]['xnat-host-path'];
				// TODO this is bad - assumes that archive dir is always "archive" (used to assume /data/archive)
                if (inputMount === undefined || !inputMount.includes("/archive/")) continue;
				inputMount = inputMount.replace(/.*\/archive\//,'');
                var inputMountEls = inputMount.split('/');
                project = inputMountEls[0];
                found = 1;
                break;
            }
            if (found) {
                return project;
            } else {
                return false;
            }
        } else {
            return false;
        }
    }

    function spawnHistoryTable(sortedHistoryObj) {

        var $dataRows = [];

        var styles = {
            command: (200 - 24) + 'px',
            user: (90 - 24) + 'px',
            DATE: (100 - 24) + 'px',
            ROOTELEMENT: (120 - 24) + 'px'
        };
        // var altStyles = {};
        // forOwn(styles, function(name, val){
        //     altStyles[name] = (val * 0.8)
        // });
        return {
            kind: 'table.dataTable',
            name: 'containerHistoryTable',
            id: 'container-history-table',
            // load: URL,
            data: sortedHistoryObj,
            before: {
                filterCss: {
                    tag: 'style|type=text/css',
                    content: '\n' +
                    '#command-history-container td.history-id { width: ' + styles.id + '; } \n' +
                    '#command-history-container td.user .truncate { width: ' + styles.user + '; } \n' +
                    '#command-history-container td.date { width: ' + styles.date + '; } \n' +
                    '#command-history-container tr.filter-timestamp { display: none } \n'
                }
            },
            table: {
                classes: 'highlight hidden',
                on: [
                    ['click', 'a.view-container-history', viewHistoryDialog]
                ]
            },
            trs: function (tr, data) {
                tr.id = data.id;
                addDataAttrs(tr, {filter: '0'});
            },
            sortable: 'id, command, user, DATE, ROOTELEMENT, status',
            filter: 'id, command, user, DATE, ROOTELEMENT, status',
            items: {
                // by convention, name 'custom' columns with ALL CAPS
                // 'custom' columns do not correspond directly with
                // a data item
                id: {
                    label: 'ID',
                    th: { style: { width: '100px' }},
                    td: { style: { width: '100px' }},
                    filter: function(table){
                        return spawn('div.center', [ XNAT.ui.input({
                            element: {
                                placeholder: 'Filter',
                                size: '9',
                                on: { keyup: function(){
                                    var FILTERCLASS = 'filter-id';
                                    var selectedValue = parseInt(this.value);
                                    $dataRows = $dataRows.length ? $dataRows : $$(table).find('tbody').find('tr');
                                    if (!selectedValue && selectedValue.toString() !== '0') {
                                        $dataRows.removeClass(FILTERCLASS);
                                    }
                                    else {
                                        $dataRows.addClass(FILTERCLASS).filter(function(){
                                            // remove zero-padding
                                            var queryId = parseInt($(this).find('td.id .sorting').html()).toString();
                                            return (queryId.indexOf(selectedValue) >= 0);
                                        }).removeClass(FILTERCLASS)
                                    }
                                } }
                            }

                        }).element ])
                    },
                    apply: function(){
                        return '<i class="hidden sorting">'+ zeroPad(this.id, 8) +'</i>'+ this.id.toString();
                    }
                },
                DATE: {
                    label: 'Date',
                    th: {className: 'container-launch'},
                    td: {className: 'container-launch'},
                    filter: function (table) {
                        var MIN = 60 * 1000;
                        var HOUR = MIN * 60;
                        var X8HRS = HOUR * 8;
                        var X24HRS = HOUR * 24;
                        var X7DAYS = X24HRS * 7;
                        var X30DAYS = X24HRS * 30;
                        return spawn('!', [XNAT.ui.select.menu({
                            value: 0,
                            options: {
                                all: {
                                    label: 'All',
                                    value: 0,
                                    selected: true
                                },
                                lastHour: {
                                    label: 'Last Hour',
                                    value: HOUR
                                },
                                last8hours: {
                                    label: 'Last 8 Hrs',
                                    value: X8HRS
                                },
                                last24hours: {
                                    label: 'Last 24 Hrs',
                                    value: X24HRS
                                },
                                lastWeek: {
                                    label: 'Last Week',
                                    value: X7DAYS
                                },
                                last30days: {
                                    label: 'Last 30 days',
                                    value: X30DAYS
                                }
                            },
                            element: {
                                id: 'filter-select-container-timestamp',
                                on: {
                                    change: function () {
                                        var FILTERCLASS = 'filter-timestamp';
                                        var selectedValue = parseInt(this.value, 10);
                                        var currentTime = Date.now();
                                        $dataRows = $dataRows.length ? $dataRows : $$(table).find('tbody').find('tr');
                                        if (selectedValue === 0) {
                                            $dataRows.removeClass(FILTERCLASS);
                                        }
                                        else {
                                            $dataRows.addClass(FILTERCLASS).filter(function () {
                                                var timestamp = this.querySelector('input.container-timestamp');
                                                var containerLaunch = +(timestamp.value);
                                                return selectedValue === containerLaunch - 1 || selectedValue > (currentTime - containerLaunch);
                                            }).removeClass(FILTERCLASS);
                                        }
                                    }
                                }
                            }
                        }).element])
                    },
                    apply: function () {
                        var timestamp = 0, dateString;
                        if (this.history.length > 0) {
                            this.history.forEach(function (h) {
                                if (h['status'] === 'Created') {
                                    timestamp = h['time-recorded'];
                                    dateString = new Date(timestamp);
                                    dateString = dateString.toISOString().replace('T', ' ').replace('Z', ' ').split('.')[0];
                                }
                            });
                        } else {
                            dateString = 'N/A';
                        }
                        return spawn('!', [
                            spawn('span', dateString),
                            spawn('input.hidden.container-timestamp.filtering|type=hidden', {value: timestamp})
                        ])
                    }
                },
                command: {
                    label: 'Command',
                    filter: true,
                    td: { style: { 'max-width': '200px', 'word-wrap': 'break-word', 'overflow-wrap': 'break-word' }},
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
                    label: 'User',
                    filter: true,
                    apply: function () {
                        return this['user-id']
                    }
                },
                ROOTELEMENT: {
                    label: 'Root Element',
                    td: { style: { 'max-width': '145px', 'word-wrap': 'break-word', 'overflow-wrap': 'break-word' }},
                    filter: true,
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
                    label: 'Status',
                    filter: true,
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

            if (dataJson.timestamp !== -1) {
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
                        XNAT.ui.dialog.alert('Processing competed');
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
                errorHandler(e, 'Cannot retrieve ' + logFile + '; container may have restarted.', true);
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
                var curid = '#wfmodal' + val;
                formattedVal = spawn('a' + curid, {}, val);
                $(document).on('click', curid, {wfid: val}, historyTable.workflowModal);
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
                historyDialogButtons.push({
                    label: 'View StdOut.log',
                    close: false,
                    action: function(){
                        var jobid = historyEntry['container-id'];
                        if (!jobid || jobid === "") {
                            jobid = historyEntry['service-id'];
                        }
                        historyTable.viewLog(jobid,'stdout')
                    }
                });

                historyDialogButtons.push({
                    label: 'View StdErr.log',
                    close: false,
                    action: function(){
                        var jobid = historyEntry['container-id'];
                        if (!jobid || jobid === "") {
                            jobid = historyEntry['service-id'];
                        }
                        historyTable.viewLog(jobid,'stderr')
                    }
                })
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

    historyTable.context = 'site';

    historyTable.init = historyTable.refresh = function (context) {
        if (context !== undefined) {
            historyTable.context = context;
        }
        else context = 'site';

        wrapperList = getObject(XNAT.plugin.containerService.wrapperList || {});

        var $manager = $('#command-history-container'),
            _historyTable;

        $manager.text("Loading...");

        sortHistoryData(context).done(function (data) {
            if (data.length) {
                // sort list of container launches by execution time, descending
                data = data.sort(function (a, b) {
                    return (a.id < b.id) ? 1 : -1
                    // return (a.history[0]['time-recorded'] < b.history[0]['time-recorded']) ? 1 : -1
                });

                // Collect status summary info
                var containerStatuses = {};
                var statusCountMsg = "";
                data.forEach(function (a) {
                    if (a.status in containerStatuses) {
                        var cnt = containerStatuses[a.status];
                        containerStatuses[a.status] = ++cnt;
                    } else {
                        containerStatuses[a.status] = 1;
                    }
                });
                for (var k in containerStatuses) {
                    if (containerStatuses.hasOwnProperty(k)) {
                        statusCountMsg += k + ":" + containerStatuses[k] + ", ";
                    }
                }
                if (statusCountMsg) {
                    statusCountMsg = ' (' + statusCountMsg.replace(/, $/,'') + ')';
                }

                _historyTable = XNAT.spawner.spawn({
                    historyTable: spawnHistoryTable(data)
                });
                _historyTable.done(function () {
                    function msgLength(length) {
                        return (length > 1) ? ' Containers' : ' Container'; 
                    }
                    var msg = (context === 'site') ?
                        data.length + msgLength(data.length) + ' Launched On This Site' :
                        data.length + msgLength(data.length) + ' Launched For '+context;

                    msg += statusCountMsg;

                    $manager.empty().append(
                        spawn('div.data-table-actionsrow', {}, [
                            spawn('strong', {class: "textlink-sm data-table-action"}, msg),
                            "&nbsp;&nbsp;",
                            spawn('button|onclick="XNAT.plugin.containerService.historyTable.refresh(\''+context+'\')"', {class: "btn btn-sm data-table-action"}, "Reload")
                        ])
                    );
                    this.render($manager, 20);
                });
            }
            else {
                $manager.empty().append('No history entries to display')
            }
        });
    };

}));
