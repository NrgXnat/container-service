console.log('activityMonitoring.js');

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
    XNAT.plugin =
        getObject(XNAT.plugin || {});

    XNAT.plugin.containerService =
        getObject(XNAT.plugin.containerService || {});

    XNAT.plugin.batchLaunch =
        getObject(XNAT.plugin.batchLaunch || {});

    XNAT.plugin.containerService.setPercentText = function(itemDivPercent, titlePercent, percentComplete) {
            $(itemDivPercent).text(percentComplete);
            $(titlePercent).text(percentComplete);
    }

    XNAT.plugin.containerService.buildStatusDisplay = function(detailsTag, total, steps, itemIds, workflowsOrganizedByElements) {
        const workflowStatusTable = 'workflowStatusTable';
         const $detailsDiv = $(detailsTag);
        //Clear existing table
        var t = document.getElementById(workflowStatusTable);
        if (t) {
           t.remove();
        }

         const table = document.createElement('table');
         table.setAttribute('id', workflowStatusTable);
         const thead = table.createTHead();
         const tbody = table.createTBody();
         var headerRow = document.createElement('tr');
         var th = document.createElement('th');
         th.appendChild(document.createTextNode('Item'));
         headerRow.appendChild(th);
         for (let col = 0; col < steps; col++) {
             let th = document.createElement('th');
             th.appendChild(document.createTextNode('Step' + (col+1)));
             headerRow.appendChild(th);
         }
         thead.appendChild(headerRow);

         if (itemIds.length === 0) {
             const row = tbody.insertRow();
             let itemIdCell = row.insertCell();
             itemIdCell.setAttribute("colspan", (steps + 1));
             itemIdCell.textContent = "Awaiting status updates....";
         } else {
         // Create table body rows
         for (let i = 0; i < itemIds.length; i++) {
             const row = tbody.insertRow();
             let itemIdCell = row.insertCell();
             let item = workflowsOrganizedByElements[i];
             itemIdCell.textContent = item['itemId'] ;
             columns = item.steps;
             for (let j = 0; j < columns.length; j++) {
               let columnItem = item.steps[j];
               let linkCell = row.insertCell();
               let link = document.createElement('a');
               link.setAttribute('onclick', columnItem['onClick']);
               link.setAttribute('title', columnItem['status']);
               link.textContent = columnItem['hrefText'];
               linkCell.appendChild(link);
               let span = document.createElement('span');
               span.innerHTML = columnItem['icon'];
               linkCell.appendChild(span);
             }
             let j=0;
             while (j<(steps - columns.length)) {
                 let awaitingCell = row.insertCell();
                 awaitingCell.textContent = "..." ;
                 ++j;
             }
         }
         }
         table.style.borderCollapse = "collapse";
         table.querySelectorAll('th, td').forEach(cell => cell.style.border = '1px solid black');
         $detailsDiv.append(table);
     }

    XNAT.plugin.containerService.updateBulkLaunchProgress = function(itemDivId, detailsTag, jsonobj, lastProgressIdx) {
        if (!lastProgressIdx) {
            lastProgressIdx = -1;
        }
        const succeeded = jsonobj['succeeded'];
        const payload = JSON.parse(jsonobj['payload']);
        const total = payload['total'];
        const targetClass = 'overall';
        const $itemDivPercent = $(itemDivId).find('.percentComplete');
        const $detailsDiv = $(detailsTag);
        const $titlePercent = $(detailsTag).closest('.xnat-dialog').find('.xnat-dialog-title .percentComplete');
        let $progDiv = $detailsDiv.find('div.' + targetClass);
        if ($progDiv.length === 0) {
            $detailsDiv.append('<div class="prog info ' + targetClass + '">Working...</div>');
            $progDiv = $detailsDiv.find('div.' + targetClass);
        }
        if (total === -1) {
            return [null, lastProgressIdx];
        }

        let steps = 1;
        if (payload['steps']) {
            steps = payload['steps'];
        }
        const workflows = payload['workflows'];
        let workflowCount = 0;
        let itemIds = [];
        if (workflows) {
             $.each(workflows, function(k, v) {
                if (itemIds.indexOf(v['itemId']) === -1 ) {
                   itemIds.push(v['itemId'])
                }
                if (v['status'] === 'Complete') {
                   ++workflowCount;
                }
             });
        }
        itemIds.sort();
        const successCount = payload['successCount'];
        const failureCount = payload['failureCount'];
        const totalJobs = total*steps;
        let percentComplete = (workflowCount / totalJobs) * 100;
        percentComplete = Math.round((percentComplete + Number.EPSILON) * 100) / 100;
        XNAT.plugin.containerService.setPercentText($itemDivPercent, $titlePercent, percentComplete);

        let clazz;
        $progDiv.text(percentComplete + '% complete (' + successCount + ' succeeded, ' + failureCount + ' failed)');
        let workflowsOrganizedByElements = [];
        for (let i = 0; i < itemIds.length; i++) {
           let itemDetails = {};
           itemDetails.itemId = itemIds[i];
           itemDetails.steps = [];
           workflowsOrganizedByElements[i] = itemDetails;
        }

        if (workflows) {
            $.each(workflows, function(k, v) {
                let index = itemIds.indexOf(v['itemId']);
                let icon = '';
                if (v['status'].toLowerCase().includes('failed')) {
                    icon = '<i class="fa-regular fa-circle-xmark"></i>';
                    clazz = 'error';
                } else if (v['status'].toLowerCase() === 'complete') {
                    icon = '<i class="fa-regular fa-square-check"></i>';
                    clazz = 'success';
                } else if (v['status'].toLowerCase() === 'running') {
                    icon = '<i class="fa-solid fa-person-running"></i>';
                } else {
                    clazz = 'info';
                }
                let currentStepId = v['currentStepId'];
                if (currentStepId === null && steps === 1) {
                   currentStepId = 0;
                }
                const onClick = 'XNAT.plugin.batchLaunch.viewWorkflowDetails(\''+k+'\',\'' + v['containerId'] + '\')';
                let hrefText = v['pipelineName'];
                let stepRowDetails = {
                    itemId:v['itemId'],
                    stepId:currentStepId,
                    pipelineName:v['pipelineName'],
                    onClick:onClick,
                    hrefText:hrefText,
                    icon:icon,
                    status:v['status'],
                    clazz:clazz
                };
                workflowsOrganizedByElements[index].steps[currentStepId] = stepRowDetails;
            });
        }
        XNAT.plugin.containerService.buildStatusDisplay(detailsTag, total, steps, itemIds, workflowsOrganizedByElements);
        if (succeeded != null) {
            clazz = succeeded ? 'success' : 'error';
            //This hack is required some containers may be in Die or Finalizing state, we are only counting the ones which are Complete
            XNAT.plugin.containerService.setPercentText($itemDivPercent, $titlePercent, 100);
            $progDiv.text(jsonobj['finalMessage']).removeClass('info').addClass(clazz);
        }
        return {succeeded: succeeded, lastProgressIdx: lastProgressIdx};
    };

 }));