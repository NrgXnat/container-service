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

        const successCount = payload['successCount'];
        const failureCount = payload['failureCount'];
        let percentComplete = (successCount + failureCount) / total * 100;
        percentComplete = Math.round((percentComplete + Number.EPSILON) * 100) / 100;
        if (percentComplete === 100 && succeeded == null) {
            percentComplete = 99;
        }
        $itemDivPercent.text(percentComplete);
        $titlePercent.text(percentComplete);

        let clazz;
        $progDiv.text(percentComplete + '% complete (' + successCount + ' succeeded, ' + failureCount + ' failed)');
        if (payload['workflows']) {
            $.each(payload['workflows'], function(k, v) {
                const wfid = 'wf' + k;
                const details = v['details'] ? ' (' + v['details'] + ')' : '';
                const message = '<a onclick="XNAT.plugin.batchLaunch.viewWorkflowDetails(\''+k+'\',\'' + v['containerId'] + '\')">'
                    + v['itemId'] + '</a>: ' + v['status'] + details;
                if (v['status'].toLowerCase().includes('failed')) {
                    clazz = 'error';
                } else if (v['status'].toLowerCase() === 'complete') {
                    clazz = 'success';
                } else {
                    clazz = 'info';
                }
                let $wfDiv = $detailsDiv.find('#' + wfid);
                if ($wfDiv.length === 0) {
                    $detailsDiv.append('<div id="' +wfid + '" class="prog ' + clazz + '">' + message + '</div>')
                } else {
                    $wfDiv.html(message).removeClass('info').addClass(clazz);
                }
            });
        }
        if (succeeded != null) {
            clazz = succeeded ? 'success' : 'error';
            $progDiv.text(jsonobj['finalMessage']).removeClass('info').addClass(clazz);
        }
        return {succeeded: succeeded, lastProgressIdx: lastProgressIdx};
    };
}));