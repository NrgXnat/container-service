/*
 * web: commandUiLauncher.js
 * XNAT http://www.xnat.org
 * Copyright (c) 2005-2017, Washington University School of Medicine and Howard Hughes Medical Institute
 * All Rights Reserved
 *
 * Released under the Simplified BSD.
 */

/*!
 * Flexible script to be used in the UI to launch
 */

console.log('commandUiLauncher.js');

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

    var launcher,
        undefined,
        launcherMenu = $('#container-launch-menu'),
        rootUrl = XNAT.url.rootUrl,
        csrfUrl = XNAT.url.csrfUrl,
        projectId = XNAT.data.context.projectID,
        xsiType = XNAT.data.context.xsiType,
        containerMenuItems;

    XNAT.plugin =
        getObject(XNAT.plugin || {});

    XNAT.plugin.containerService =
        getObject(XNAT.plugin.containerService || {});

    XNAT.plugin.containerService.launcher = launcher =
        getObject(XNAT.plugin.containerService.launcher || {});

    launcher.swarmConstraintsStr = 'swarm-constraints'; // must match CommandResolutionServiceImpl>swarmConstraintsTag


    function errorHandler(e){
        var details = e.responseText ? spawn('p',[e.responseText]) : '';
        console.log(e);
        xmodal.alert({
            title: 'Error',
            content: '<p><strong>Error ' + e.status + ': '+ e.statusText+'</strong></p>' + details.html,
            okAction: function () {
                xmodal.closeAll();
            }
        });
    }
    function uniqueArray(value, index, self) {
        return self.indexOf(value) === index;
    }
    function getEnabledCommandsUrl(appended){
        appended = isDefined(appended) ? appended : '';
        return rootUrl('/xapi/commands' + appended);
    }

    function getCommandUrl(commandId,wrapperName,appended){
        if (!commandId || !wrapperId) return false;
        appended = (appended) ? '/' + appended : '';
        return rootUrl('/xapi/commands/'+commandId+'/wrappers/'+wrapperName+appended);
    }
    function getLauncherUI(wrapperId,rootElementName,rootElementValue){
        return rootUrl('/xapi/wrappers/'+wrapperId+'/launch?'+rootElementName+'='+rootElementValue);
    }
    function getProjectLauncherUI(wrapperId,rootElementName,rootElementValue){
        return rootUrl('/xapi/projects/'+projectId+'/wrappers/'+wrapperId+'/launch?'+rootElementName+'='+rootElementValue);
    }
    function getBulkLauncherUI(wrapperId,project) {
        var projectStr = (project) ? '/projects/' + project : '';
        return rootUrl('/xapi' + projectStr + '/wrappers/' + wrapperId + '/bulklaunch');
    }
    function containerLaunchUrl(wrapperId,rootElementName){
        return csrfUrl('/xapi/wrappers/'+wrapperId+'/root/'+rootElementName+'/launch');
    }
    function projectContainerLaunchUrl(project,wrapperId,rootElementName){
        return csrfUrl('/xapi/projects/'+project+'/wrappers/'+wrapperId+'/root/'+rootElementName+'/launch');
    }
    function bulkLaunchUrl(wrapperId,rootElementName,project){
        return (project)
            ? csrfUrl('/xapi/projects/'+project+'/wrappers/'+wrapperId+'/root/'+rootElementName+'/bulklaunch')
            : csrfUrl('/xapi/wrappers/'+wrapperId+'/root/'+rootElementName+'/bulklaunch');
    }
    function sessionUrl(){
        var sessionId = (XNAT.data.context.isImageSession) ? XNAT.data.context.ID : null;
        if (!sessionId) return false;
        return rootUrl('/REST/experiments/'+sessionId);
    }
    function fullScanPath(scanId){
        var sessionId = (XNAT.data.context.isImageSession) ? XNAT.data.context.ID : null;
        if (!sessionId) return false;
        return '/archive/experiments/'+sessionId+'/scans/'+scanId;
    }

    /*
     * Launcher UI Builder (i.e. CommandResolver)
     */

    /*
     * Panel form elements for launcher
     */

    var helptext = function(description) {
        return (description) ? '' : spawn('div.description',description);
    };
    var vertSpace = function(condensed) {
        return (condensed) ? '' : spawn('br.clear');
    };

    var defaultConfigInput = function(input){
        var name = input.name || input.label,
            value = input.value,
            label = input.label,
            description = input.description || '',
            required = input.required || false,
            classes = ['panel-input'],
            dataProps = {};
        value = (value === undefined || value === null || value == 'null') ? '' : value;
        label = label || name;
        description = description || '';

        if (input.childInputs) {
            classes.push('has-children');
            dataProps['children'] = input.childInputs.join(',');
        }

        if (required) {
            classes.push('required');
            description += ' (Required)';
        }

        return XNAT.ui.panel.input.text({
            name: name,
            value: value,
            description: description,
            label: label,
            data: dataProps,
            className: classes.join(' ')
        }).element;
    };

    var booleanEval = function(val){
        var trueValues = ['1','y','yes','true','t'];
        return (trueValues.indexOf(val.toString().toLowerCase()) >= 0 );
    };

    var configCheckbox = function(input){
        var name = input.name || input.outerLabel,
            value = input.value,
            checked = input.checked,
            boolean = input.boolean,
            outerLabel = input.outerLabel,
            innerLabel = input.innerLabel,
            description = input.description || '',
            required = input.required || false,
            condensed = input.condensed || false,
            classes = ['panel-element panel-input'],
            dataProps = { name: name },
            disabled = input.disabled || false,
            attr = {};

        if (input.childInputs) {
            classes.push('has-children');
            dataProps['children'] = input.childInputs.join(',');
        }

        if (checked === 'checked') attr['checked'] = 'checked';
        if (disabled) attr['disabled'] = 'disabled';

        value = (boolean) ? 'true' : value;

        if (required) {
            classes.push('required');
            description += ' (Required)';
        }

        return spawn('div', { className: classes.join(' '), data: dataProps }, [
            spawn('label.element-label', outerLabel),
            spawn('div.element-wrapper', [
                spawn('label', [
                    spawn('input', { type: 'checkbox', name: name, value: value, attr: attr }),
                    innerLabel
                ]),
                helptext(description)
            ]),
            vertSpace(condensed)
        ]);
    };

    var configRadio = function(input){
        var name = input.name || input.outerLabel,
            value = input.value,
            checked = input.checked,
            boolean = input.boolean,
            outerLabel = input.outerLabel,
            innerLabel = input.innerLabel,
            description = input.description || '',
            required = input.required || false,
            condensed = input.condensed || false,
            classes = ['panel-element panel-input'],
            dataProps = { name: name },
            disabled = input.disabled || false,
            attr = {};

        if (input.childInputs) {
            classes.push('has-children');
            dataProps['children'] = input.childInputs.join(',');
        }

        if (checked === 'true') attr['checked'] = 'checked';
        if (disabled) attr['disabled'] = 'disabled';

        if (required) {
            classes.push('required');
            description += ' (Required)';
        }

        return spawn('div', { className: classes.join(' '), data: dataProps }, [
            spawn('label.element-label', outerLabel),
            spawn('div.element-wrapper', [
                spawn('label', [
                    spawn('input', { type: 'radio', name: name, value: value, attr: attr }),
                    innerLabel
                ]),
                helptext(description)
            ]),
            vertSpace(condensed)
        ]);
    };

    var configSelect = function(input){
        var name = input.name,
            label = input.label,
            dataProps = input.dataProps || {},
            classes = ['panel-element panel-input'],
            attr = (input.disabled) ? { 'disabled':'disabled'} : {},
            description = input.description || '',
            required = input.required || false;

        if (required) {
            classes.push('required');
            description += ' (Required)';
        }

        if (input.childInputs) {
            classes.push('has-children');
            dataProps['children'] = input.childInputs.join(',');
        }

        return XNAT.ui.panel.select.single({
            name: name,
            label: label,
            data: dataProps,
            attr: attr,
            className: classes.join(' '),
            description: description,
            options: input.options
        }).element;
    };

    var configSelectMany = function(input){
        var name = input.name,
            label = input.label,
            dataProps = input.dataProps || {},
            classes = ['panel-element panel-input'],
            attr = (input.disabled) ? { 'disabled':'disabled'} : {},
            description = input.description || '',
            required = input.required || false;

        if (required) {
            classes.push('required');
            description += ' (Required)';
        }

        // Cannot have children if multiple select
        // if (input.childInputs) {
        //     classes.push('has-children');
        //     dataProps['children'] = input.childInputs.join(',');
        // }

        return XNAT.ui.panel.select.multi({
            name: name,
            label: label,
            data: dataProps,
            attr: attr,
            className: classes.join(' '),
            description: description,
            options: input.options
        }).element;
    };

    var hiddenConfigInput = function(input) {
        var name = input.name || input.label,
            value = input.value,
            dataProps = {},
            classes = [],
            attr = (input.disabled) ? { 'disabled':'disabled' } : {};

        if (input.childInputs) {
            classes.push('has-children');
            dataProps['children'] = input.childInputs.join(',');
        }

        return XNAT.ui.input.hidden({
            name: name,
            value: value,
            data: dataProps,
            attr: attr
        }).element;
    };

    var staticConfigInput = function(input) {
        var name = input.name || input.label,
            label = input.label,
            value = input.value,
            valueLabel = input.valueLabel,
            dataProps = { name: name },
            classes = ['panel-input','panel-element'],
            attr = (input.disabled) ? { 'disabled':'disabled' } : {};

        if (input.childInputs) {
            classes.push('has-children');
            dataProps['children'] = input.childInputs.join(',');
        }

        return spawn(
            'div', { className: classes.join(' '), data: dataProps }, [
                spawn('label.element-label', label),
                spawn('div.element-wrapper', { style: { 'word-wrap': 'break-word' } }, valueLabel),
                spawn('input',{
                    type: 'hidden',
                    name: name,
                    value: value,
                    data: dataProps,
                    attr: attr
                }),
                spawn('br.clear')
            ]
        );
    };

    var staticConfigList = function(input) {
        var value = input.value,
            name = input.name,
            label = input.label || input.name,
            valueLabel = input.valueLabel;
        var listArray = Array.isArray(valueLabel) ? valueLabel : valueLabel.split(",");
        var elemWrapperContent;
        if (listArray.length > 6) {
            elemWrapperContent = spawn('textarea',{ 'readonly':true, style: { height: '80px' }},listArray.join('\n'));
        } else {
            listArray.forEach(function(item,i){
                listArray[i] = '<li>'+item+'</li>'
            });
            elemWrapperContent = spawn('ul',{ style: {
                    'list-style-type': 'none',
                    margin: 0,
                    padding: 0
                }},listArray.join(''));
        }
        return spawn(
            'div.panel-element', { data: { name: name } }, [
                spawn('label.element-label', label),
                spawn('div.element-wrapper', [elemWrapperContent]),
                spawn('input',{
                    type: 'hidden',
                    name: name,
                    value: JSON.stringify(value)
                }),
                spawn('br.clear')
            ]
        )
    };

    var derivedConfigInput = function(input) {
        var name = input.name || input.label,
            label = input.label,
            value = input.value,
            valueLabel = input.valueLabel,
            dataProps = { name: name },
            classes = ['panel-input','panel-element'];

        return spawn(
            'div', { className: classes.join(' '), data: dataProps }, [
                spawn('label.element-label', label),
                spawn('div.element-wrapper', { style: { 'word-wrap': 'break-word' } }, [
                    valueLabel,
                    spawn('div.description', value)
                ]),
                spawn('br.clear')
            ]
        );
    };

    launcher.errorMessages = [];

    launcher.formInputs = function(input) {
        var formPanelElements = [];

        // create a panel.input for each input type
        switch (input.type) {
            case 'scanSelectMany':
                launcher.scanList.forEach(function (scan, i) {
                    var scanOpts = {
                        name: 'scan',
                        value: fullScanPath(scan.id),
                        innerLabel: scan.id + ' - ' + scan['series_description'],
                        condensed: true
                    };
                    if (i === 0) {
                        // first
                        scanOpts.outerLabel = 'scans';
                        formPanelElements.push(configCheckbox(scanOpts));
                    } else if (i < launcher.scanList.length - 1) {
                        // middle
                        formPanelElements.push(configCheckbox(scanOpts));
                    } else {
                        // last
                        scanOpts.condensed = false;
                        formPanelElements.push(configCheckbox(scanOpts));
                    }
                });
                break;
            case 'hidden':
                formPanelElements.push(hiddenConfigInput(input));
                break;
            case 'static':
                formPanelElements.push(staticConfigInput(input));
                break;
            case 'staticList':
                formPanelElements.push(staticConfigList(input));
                break;
            case 'checkbox':
                input.outerLabel = input.label;
                input.innerLabel = input.innerLabel || input.value;
                formPanelElements.push(configCheckbox(input));
                break;
            case 'radio':
                input.outerLabel = input.label;
                input.innerLabel = input.innerLabel || input.value;
                formPanelElements.push(configRadio(input));
                break;
            case 'select-one':
                formPanelElements.push(configSelect(input));
                break;
            case 'select-many':
                formPanelElements.push(configSelectMany(input));
                break;
            case 'boolean':
                input.boolean = true;
                input.outerLabel = input.label;
                input.innerLabel = input.innerLabel || 'True';
                input.checked = (booleanEval(input.value)) ? 'checked' : false;
                formPanelElements.push(configCheckbox(input));
                break;
            case 'derived':
                formPanelElements.push(derivedConfigInput(input));
                break;
            case 'ignore':
                break;
            default:
                formPanelElements.push(defaultConfigInput(input));
        }

        return formPanelElements;

    };

    launcher.populateForm = function($form, inputList, inputValues, rootElement){
        // receive $form as a jquery form object
        // receives optional input list, which can define just a subgroup of inputs to render.
        // requires input list and default values to be stored in the XNAT.plugin.containerService.launcher object
        // get input values via JSONpath queries

        rootElement = rootElement || false;

        function findInput(inputName, $form){
            return $form.find('input[name='+inputName+']').length ||
                $form.find('select[name='+inputName+']').length ||
                $form.find('textarea[name='+inputName+']').length;
        }

        function setValue(input, newValue, $form){
            newValue = newValue || '';
            switch (input.type) {
                case 'radio' :
                    if (newValue) {
                        var $radioOpt = $form.find('input[name='+input.name+'][value='+newValue+']');
                        try{
                            $radioOpt.prop('checked','checked');
                        }
                        catch(e){
                            console.log('Could not find option with '+newValue+'. ',e);
                        }
                    } else {
                        $form.find('input[name='+input.name+']:checked').prop('checked',false);
                    }
                    break;
                case 'boolean' :
                    if (newValue) {
                        var $radioOpt = $form.find('input[name='+input.name+'][value='+newValue+']');
                        try{
                            $radioOpt.prop('checked','checked');
                        }
                        catch(e){
                            console.log('Could not find option with '+newValue+'. ',e);
                        }
                    } else {
                        $form.find('input[name='+input.name+']:checked').prop('checked',false);
                    }
                    break;
                case 'static' :
                    // newValue should be delivered as an object
                    $form.find('input[name='+input.name+']')
                        .val(newValue.value)
                        .parent('.panel-input').find('.element-wrapper').html(newValue.label);
                    break;
                case 'select-one' :
                case 'select-many' :
                    var $select = $form.find('select[name='+input.name+']');
                    if (newValue) {
                        $select.find('option[value='+newValue+']').prop('selected','selected');
                    } else
                    {
                        $select.find('option:selected').prop('selected',false);
                    }
                    break;
                case 'derived' :
                case 'ignore' :
                    //Shouldn't ever change.
                    break;
                default :
                    // text input
                    $form.find('input[name='+input.name+']').val(newValue);
                    break;
            }
        }

        function renderInput(input, $form, valueArr){
            var $standardInputContainer = $form.find('.standard-settings');
            var $advancedInputContainer = $form.find('.advanced-settings');

            var selectedVal,
                selectedLabel,
                inputValues = launcher.inputPresets,
                valueArr = valueArr || jsonPath(inputValues, "$..[?(@.name=='"+input.name+"')].values[*]"),
                configInput = extend({}, input),
                derivedInput = configInput.hasOwnProperty('input-type') && configInput['input-type'] === 'derived';

            configInput.type = (configInput['user-settable'] || configInput.name === rootElement || derivedInput) ?
                configInput['input-type'] : 'static';

            if (derivedInput) {
                selectedLabel = configInput['derived-value-info'];
                selectedVal = configInput['derived-value-message'];
            } else {
                valueArr = !isArray(valueArr) ? [] : valueArr.filter(uniqueArray);

                // HACK HACK HACK -- The top level element may return improperly-formatted JSON from the jsonPath query.
                if (valueArr.length > 0 && valueArr[0].values !== undefined) valueArr = valueArr[0].values;

                if (input['select-values'].length > 0) {
                    // valueArr will contain any default values
                    var selectedValues = [];
                    if (valueArr.length > 0) {
                        selectedValues = $.map(valueArr, function(val){
                            try {
                                return JSON.parse(val.value);
                            } catch (e) {
                                return val.value;
                            }
                        }).flat();
                    }
                    var options = {};
                    if (input['input-type'] === "select-one" && selectedValues.length === 0) {
                        options['default'] = {label: 'Select one', selected: true};
                    }
                    input['select-values'].forEach(function (val, i) {
                        options['option-' + i] = {value: val, label: val, selected: selectedValues.contains(val), children: []};
                    });
                    configInput.options = options;
                } else {
                    if (valueArr.length === 0) {
                        // if no values can be set, render the input without a value selected
                        selectedVal = '';
                        selectedLabel = '';
                    } else {
                        if (input['input-type'] === "select-one") {
                            var options = {'default': {label: 'Select one', selected: true}};
                            valueArr.forEach(function (val, i) {
                                options['option-' + i] = val
                            });
                            configInput.options = options;

                            configInput.dataProps = {'childValues': JSON.stringify(valueArr)};
                        } else if (input['input-type'] === "select-many") {
                            var options = {};
                            valueArr.forEach(function (val, i) {
                                options['option-' + i] = val
                            });
                            configInput.options = options;
                        } else if (valueArr.length > 1) {
                            // Multiple matches, usually derived inputs
                            if (input['multiple']) {
                                selectedVal = JSON.stringify($.map(valueArr, function (v) {
                                    return v.value;
                                }));
                                selectedLabel = $.map(valueArr, function (v) {
                                    return v.label;
                                }).join('<br/>');
                            } else {
                                // if multiple options exist for an input that isn't designated as a select, treat it as a dependent child
                                selectedVal = '';
                                selectedLabel = '';
                            }
                        } else {
                            selectedVal = valueArr[0].value;
                            selectedLabel = valueArr[0].label || 'N/A';
                        }
                    }
                }
            }

            if (findInput(input.name, $form)) {
                // don't render a new input, change the value instead
                var value;
                switch(configInput['input-type']){
                    case 'static':
                    case 'derived':
                        value = {
                            value: selectedVal,
                            label: selectedLabel
                        };
                        break;
                    case 'select-one':
                    case 'select-many':
                        // re-render select menu to reflect updated options
                        var thisSelect = $form.find('select[name='+configInput.name+']');
                        thisSelect.empty();
                        for (var opt in configInput.options){
                            var optHtml = '<option ';
                            optHtml += (configInput.options[opt]) ? 'value = "'+configInput.options[opt].value+'"' : '';
                            if (configInput.options[opt].attr) {
                                for (var attr in configInput.options[opt].attr) { optHtml += ' '+attr+'="'+configInput.options[opt].attr[attr]+'"'; }
                            }
                            optHtml += '>'+configInput.options[opt].label+'</option>';
                            thisSelect.append(optHtml);
                        }

                        // also update its child value data object
                        thisSelect.data('childValues',JSON.stringify(valueArr));
                        break;
                    default:
                        value = selectedVal;
                        break;
                }

                setValue(configInput,value,$form);
            }
            else {
                // render a new input
                configInput = extend({
                    value: selectedVal,
                    valueLabel: selectedLabel
                }, configInput); // add value properties without impacting the canonical input list

                if (isArray(jsonPath(valueArr, "$..children[*].name"))) {
                    configInput.childInputs = jsonPath(valueArr, "$..children[*].name")
                        .filter(uniqueArray); // build an array of the input names of all child inputs. Ensure the array is unique.
                }
                var $addTo = configInput.advanced ? $advancedInputContainer : $standardInputContainer;
                $addTo.append(launcher.formInputs(configInput));
            }


            // iterate recursively over child inputs
            if (isArray(configInput.children) && configInput.children.length > 0) {
                configInput.children.forEach(function(childInput){
                    var childValues = jsonPath(valueArr,"$..[?(@.name == '"+childInput.name+"')].values[*]");
                    if (childValues && childValues.length) {
                        renderInput(childInput, $form, childValues);
                    } else {
                        renderInput(childInput, $form);
                    }
                })
            }
        }

        inputList.forEach(function(thisInput){
            // normalize format of values, only submit those pertinent to this input
            var valueArr;
            if (jsonPath(inputValues, "$..[?(@.name=='"+thisInput.name+"')]")) {
                // inputValues = inputValues.filter(function(valueSet){ return valueSet.name === thisInput.name })[0].values;
                valueArr = jsonPath(inputValues, "$..[?(@.name=='"+thisInput.name+"')].values[*]");
            }
            else {
                valueArr = inputValues;
            }

            renderInput(thisInput, $form, valueArr);
        });
    };

    launcher.addSwarmConstraintsToForm = function($container, constraints) {
        if (constraints.length > 0 && $container.find('.' + launcher.swarmConstraintsStr).length === 0) {
            var selects = [];
            selects.push(spawn('span.' + launcher.swarmConstraintsStr + '-heading', 'Swarm node constraints'));
            $.each(constraints, function(i, conf) {
                selects.push(hiddenConfigInput({
                    name: launcher.swarmConstraintsStr + '['+i+']:attribute',
                    value: conf.attribute
                }));
                selects.push(configSelect({
                    label: conf.attribute + ' ' + conf.comparator,
                    name: launcher.swarmConstraintsStr + '['+i+']:value',
                    options: conf.values
                }));
            });
            var serverConstrDiv = spawn('div.'  + launcher.swarmConstraintsStr, selects);
            $container.append($(serverConstrDiv));
        }
    };

    $(document).on('change','.has-children',function(){
        if ($(this).data('children')) {
            // var children = $(this).data('children').split(',');
            // console.log('Inputs to change: ', children);

            var $form = $(this).parents('.panel'),
                input = $(this).prop('name'),
                selectedVal = $(this).val(),
                children;

            if ($(this).data('childValues')) {
                var valueObj = $(this).data('childValues');
                valueObj = (typeof valueObj === "string") ? JSON.parse(valueObj) : valueObj;
                children = jsonPath(valueObj, "$..[?(@.value=='"+selectedVal+"')].children[*]")
                    .filter(function(value, index, self) { return self.indexOf(value) === index; });
            }
            else {
                children = jsonPath(launcher.inputPresets, "$.[?(@.name=='"+input+"')]..[?(@.value=='"+selectedVal+"')].children[*]")
                    .filter(function(value, index, self) { return self.indexOf(value) === index; });
            }

            children.forEach(function(childVal){
                var childInputList = jsonPath(launcher.inputList, "$..children[?(@.name=='"+childVal.name+"')]");
                launcher.populateForm($form, childInputList, childVal.values);
            })
        }
    });

    /*
     ** Launcher Options
     */

    function launchContainer(configData,rootElement,wrapperId,targets,targetLabels,project){
        var workList = configData['input-config'];
        launcher.inputList = configData['input-config'];
        launcher.inputPresets = configData['input-values'];

        var runLabel = 'Run Container';
        var projectContext = XNAT.data.context.project;
        var launchUrl = (projectContext.length) ?
            projectContainerLaunchUrl(projectContext,wrapperId,rootElement) :
            containerLaunchUrl(wrapperId,rootElement);
        var bulkLaunch = false;

        var formContent = [spawn('p','Please specify settings for this container.')];

        if (targets) {
            // Bulk launch
            bulkLaunch = true;
            formContent.push(spawn('div.target-list.bulk-inputs'));
            runLabel += "(s)";
            launchUrl = bulkLaunchUrl(wrapperId, rootElement, project);
        }

        formContent.push(
            spawn('div.standard-settings'),
            spawn('div.advanced-settings-container',[
                spawn('div.advanced-settings-toggle'),
                spawn('div.advanced-settings')
            ])
        );

        var launcherContent = spawn('div.panel',[spawn('form',formContent)]);
        // if the specified root element matches an input parameter, we can proceed
        if ( jsonPath(workList, "$..name").indexOf(rootElement) >=0 ) {
            XNAT.ui.dialog.open({
                title: 'Set Container Launch Values',
                content: launcherContent,
                width: 550,
                maxBtn: true,
                scroll: true,
                beforeShow: function(obj){
                    launcher.errorMessages = [];
                    xmodal.loading.close();
                    xmodal.loading.open({title: 'Rendering Container Launcher'});
                    var $panel = obj.$modal.find('.panel'),
                        $targetListContainer = $panel.find('.target-list'),
                        $advancedInputContainer = $panel.find('.advanced-settings');

                    if (bulkLaunch && $targetListContainer.length > 0) {
                        // display root elements first
                        $targetListContainer.append(spawn('p',[ spawn('strong', targets.length + ' item(s) selected to run in bulk.' )]));

                        var targetList = launcher.formInputs({
                            name: rootElement,
                            type: 'staticList',
                            valueLabel: targetLabels,
                            value: targets
                        });
                        $targetListContainer.append(targetList);

                        // dont display the root element since it's now the target list
                        workList.forEach(function(input){ if (input.name === rootElement) input['input-type'] = 'ignore' });
                    }

                    launcher.populateForm($panel, workList, launcher.inputPresets, rootElement);
                    launcher.addSwarmConstraintsToForm($advancedInputContainer, configData['container-server-config']['swarm-constraints']);
                    if ($advancedInputContainer.children().length === 0) {
                        $panel.find('div.advanced-settings-container').hide();
                    }
                },
                afterShow: function(obj){
                    xmodal.loading.close();
                    if (isArray(launcher.errorMessages) && launcher.errorMessages.length) {
                        var $panel = obj.$modal.find('.panel');
                        launcher.errorMessages.forEach(function(msg){
                            $panel.prepend(spawn('div.warning',{style: { 'margin-bottom': '1em' }},msg));
                        });
                    }
                },
                buttons: [
                    {
                        label: runLabel,
                        isDefault: true,
                        close: false,
                        action: function(obj){
                            function getLabel($element) {
                                return $element.parents('.panel-input').children('.element-label').text();
                            }
                            var $form = obj.$modal.find('.panel form');

                            // check all inputs for invalid characters
                            var $inputs = $form.find('input'),
                                errors = [];
                            $form.find('.required:not(div)').each(function(){
                                if (!XNAT.validate(this).is('required').check()) {
                                    errors.push('Required input "' + getLabel($(this)) + '" is missing');
                                }
                            });
                            $inputs.each(function(){
                                var input = $(this)[0];
                                if (!launcher.noIllegalChars(input)) {
                                    errors.push('Illegal characters were found in input "' + getLabel($(this)) + '"');
                                    $(this).addClass('invalid');
                                }
                            });

                            if (errors.length === 0) {
                                if (!bulkLaunch) xmodal.loading.open({ title: 'Launching Container...' });

                                // gather form input values
                                var dataToPost = form2js($form.get(0), ':', false);
                                // API method can only receive Map<String, String> so need to stringify more complex params
                                // with plan to deserialize them deeper in the code
                                $.each(dataToPost, function(key, value) {
                                    if ($.type(value) !== "string") {
                                        dataToPost[key] = JSON.stringify(value);
                                    }
                                });

                                XNAT.xhr.postJSON({
                                    beforeSend: function() {
                                        if (bulkLaunch) {
                                            XNAT.ui.dialog.closeAll();
                                            XNAT.ui.dialog.alert("Containers are being launched in the background. " +
                                                "You may continue to work, monitoring workflows to see updated progress.");
                                        }
                                        return true;
                                    },
                                    url: launchUrl,
                                    data: JSON.stringify(dataToPost),
                                    success: function(data){
                                        if (bulkLaunch) {
                                            // bulk launch success returns two arrays -- containers that successfully launched, and containers that failed to launch
                                            var messageContent = [],
                                                totalLaunchAttempts = data.successes.concat(data.failures).length;
                                            if (data.failures.length > 0) {
                                                messageContent.push( spawn('div.message',data.successes.length + ' of '+totalLaunchAttempts+' containers successfully queued for launch.') );
                                            } else if(data.successes.length > 0) {
                                                messageContent.push( spawn('div.success','All containers successfully queued for launch.') );
                                            } else {
                                                errorHandler({
                                                    statusText: 'Something went wrong. No containers were queued for launch.'
                                                });
                                            }

                                            if (data.successes.length > 0) {
                                                messageContent.push( spawn('h3',{'style': {'margin-top': '2em' }},'Containers successfully queued') );

                                                data.successes.forEach(function(success){
                                                    if (success['type'] === 'service') {
                                                        messageContent.push( spawn('p',[spawn('strong','Service ID: '),spawn('span',success['service-id']) ]));
                                                    } else {
                                                        messageContent.push( spawn('p',[
                                                            spawn('strong','Container ID: '),
                                                            spawn('span',success['container-id'])
                                                        ]) );
                                                    }
                                                    messageContent.push( spawn('div',prettifyJSON(success.params)) );
                                                });
                                            }

                                            if (data.failures.length > 0){
                                                messageContent.push( spawn('h3',{'style': {'margin-top': '2em' }},'Containers that couldn\'t be queued') );
                                                data.failures.forEach(function(failure){
                                                    messageContent.push( spawn('p',{ style: { 'font-weight': 'bold' }}, 'Error Message:') );
                                                    messageContent.push( spawn('pre.json', failure.message) );
                                                    messageContent.push( spawn('div',prettifyJSON(failure.params)) );
                                                });
                                            }

                                            XNAT.ui.dialog.open({
                                                title: 'Container launch report',
                                                content: spawn('div', messageContent ),
                                                buttons: [
                                                    {
                                                        label: 'OK',
                                                        isDefault: true,
                                                        close: true,
                                                        action: XNAT.ui.dialog.closeAll()
                                                    }
                                                ]
                                            });
                                        } else {
                                            xmodal.loading.close();
                                            var messageContent;
                                            if (data.status === 'success') {
                                                if ( data['type'] === 'service') {
                                                    messageContent = spawn('p',{ style: { 'word-wrap': 'break-word'}}, 'Service ID: '+data['service-id']);
                                                } else {
                                                    messageContent = spawn('p',{ style: { 'word-wrap': 'break-word'}}, 'Container ID: '+data['container-id']);
                                                }
                                            } else {
                                                messageContent = spawn('p', data.message);
                                            }

                                            XNAT.ui.dialog.open({
                                                title: 'Container Launch <span style="text-transform: capitalize">'+data.status+'</span>',
                                                content: messageContent,
                                                buttons: [
                                                    {
                                                        label: 'OK',
                                                        isDefault: true,
                                                        close: true,
                                                        action: XNAT.ui.dialog.closeAll()
                                                    }
                                                ]
                                            });
                                        }
                                    },
                                    fail: function (e) {
                                        if (!bulkLaunch) xmodal.loading.close();

                                        if (e.responseJSON && e.responseJSON.message) {
                                            var data = e.responseJSON;
                                            var messageContent = spawn('div',[
                                                spawn('p',{ style: { 'font-weight': 'bold' }}, 'Error Message:'),
                                                spawn('pre.json', data.message),
                                                spawn('p',{ style: { 'font-weight': 'bold' }}, 'Parameters Submitted To XNAT:'),
                                                spawn('div', prettifyJSON(data.params))
                                            ]);

                                            XNAT.ui.dialog.open({
                                                title: 'Container Launch <span style="text-transform: capitalize">'+data.status+'</span>',
                                                content: messageContent,
                                                buttons: [
                                                    {
                                                        label: 'OK',
                                                        isDefault: true,
                                                        close: true,
                                                        action: XNAT.ui.dialog.closeAll()
                                                    }
                                                ]
                                            });
                                        } else {
                                            errorHandler(e);
                                        }
                                    }
                                });

                            } else {
                                // don't run container if invalid characters are found
                                XNAT.dialog.open({
                                    title: 'Cannot Launch Container',
                                    content: 'Please correct the errors, below, and try again:' +
                                        '<ul>' + $.map(errors, function(e) { return '<li>' + e + '</li>';}) + '</ul>',
                                    width: 400,
                                    buttons: [
                                        {
                                            label: 'OK',
                                            isDefault: true,
                                            close: true
                                        }
                                    ]
                                });
                                return false;
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
            errorHandler({
                statusText: 'Root element mismatch',
                responseText: 'No instance of ' + rootElement + ' was found in the list of inputs for this command'
            });
        }
    }

    /* ---- Launcher Types ---- */

    launcher.defaultLauncher = function(wrapperId,rootElement,rootElementValue){
        rootElementValue = rootElementValue || XNAT.data.context.ID; // if no value is provided, assume that the current page context provides the value.

        if (!rootElementValue) {
            errorHandler({ responseText: 'Could not launch UI. No value provided for '+rootElement+'.' });
            return false;
        }

        xmodal.loading.open({ title: 'Configuring Container Launcher' });
        var launchUrl = (projectId) ? getProjectLauncherUI(wrapperId,rootElement,rootElementValue) : getLauncherUI(wrapperId,rootElement,rootElementValue);

        XNAT.xhr.getJSON({
            url: launchUrl,
            fail: function(e){
                xmodal.loading.close();
                errorHandler({
                    statusText: e.statusText,
                    responseText: 'Could not launch UI with value: "'+rootElementValue+'" for root element: "'+rootElement+'".'
                });
            },
            success: function(data){
                launchContainer(data,rootElement,wrapperId);
            }
        })
    };

    launcher.singleScanDialog = function(wrapperId,rootElementPath){
        // end goal is submitting to /xapi/commands/launch/
        // need to build UI with input values from /xapi/wrappers/{id}/launchui, specifying the root element name and path

        var launchUrl = (projectId) ? getProjectLauncherUI(wrapperId,'scan',rootElementPath) : getLauncherUI(wrapperId, 'scan', rootElementPath);

        XNAT.xhr.getJSON({
            url: launchUrl,
            fail: function(e){
                errorHandler(e);
            },
            success: function(data){
                var rootElement = 'scan';
                launchContainer(data,rootElement,wrapperId);
            }
        });
    };

    launcher.bulkLaunchDialog = function(wrapperId,rootElement,targets,targetLabels,project){
        // 'targets' should be formatted as a one-dimensional array of XNAT URIs (i.e. scan URIs, session URIs)
        // the 'root element' should match one of the inputs in the command config object, and overwrite it with the
        // values provided in the 'targets' array

        if (projectId.length && !project) project = projectId;

        if (!targets || targets.length === 0) return false;
        if (!targetLabels || targetLabels.length !== targets.length) {
            targetLabels = $.map(targets, function(t) {return t.replace(/.*\//,'');});
        }

        xmodal.loading.open({ title: 'Configuring Container Launcher' });

        var launchUrl = getBulkLauncherUI(wrapperId, project);
        var sampleTarget = targets[0];

        XNAT.xhr.getJSON({
            url: launchUrl,
            data: {'sampleTarget': sampleTarget, 'rootElement': rootElement},
            fail: function(e){
                xmodal.loading.close();
                errorHandler({
                    statusText: e.statusText,
                    responseText: 'Could not build bulk UI for root element: "' +
                        rootElement + '" sample target:"' + sampleTarget + '".'
                });
            },
            success: function(configData){
                launchContainer(configData, rootElement, wrapperId, targets, targetLabels, project);
            }
        });
    };

    launcher.noIllegalChars = function(input,exception){
        // examine the to-be-submitted value of an input against a list of disallowed characters and return false if any are found.
        // if an input needs to allow one of these strings, an exception can be passed to this function
        exception = exception || null;
        var illegalCharset = [';', '\\|\\|', '&&', '\\(', '`' ],
            value = input.value,
            pass = true;

        illegalCharset.forEach(function(test){
            if (value.match(test) && test !== exception) {
                pass = false;
            }
        });

        return pass;
    };

    /*
     * Build UI for menu selection
     */

    launcher.containerMenuItems = containerMenuItems = [
        {
            text: 'Run Containers',
            url: '#run',
            submenu: {
                id: 'containerSubmenuItems',
                itemdata: [
                ]
            }
        }
    ];

    launcher.addMenuItem = function(command,commandSet){
        commandSet = commandSet || [];
        var label = command['wrapper-name'];
        if (command['wrapper-description']) if (command['wrapper-description'].length) label = command['wrapper-description'];
        if (command['wrapper-label']) if (command['wrapper-label'].length) label = command['wrapper-label'];

        if (command.enabled){
            commandSet.push(
                spawn('li', [
                    spawn('a', {
                        html: label,
                        href: '#!',
                        className: 'commandLauncher',
                        data: {
                            wrapperid: command['wrapper-id'],
                            rootElementName: command['root-element-name'],
                            uri: command['uri'],
                            launcher: command['launcher']
                        }
                    })
                ]));
        }
        return commandSet;
    };

    launcher.createMenu = function(target,commandSet){
        /*
        var containerMenu = spawn('li.has-submenu',[
            spawn(['a',{ href: '#!', html: 'Run' }]),
            spawn(['ul.dropdown-submenu', itemSet ])
        ]);
        */

        target.append(commandSet);
    };

    /* to be replaced when we kill YUI */
    launcher.addYUIMenuItem = function(command){
        if (command.enabled) {
            var launcher = command.launcher || "default";
            var label = command['wrapper-name'];
            if (command['wrapper-description']) if (command['wrapper-description'].length) label = command['wrapper-description'];
            if (command['wrapper-label']) if (command['wrapper-label'].length) label = command['wrapper-label'];

            containerMenuItems[0].submenu.itemdata.push({
                text: label,
                url: 'javascript:openCommandLauncher({ wrapperid:"'+command['wrapper-id']+'", launcher: "'+launcher+'", rootElement: "'+ command['root-element-name'] + '" })',
                classname: 'enabled wrapped' // injects a custom classname onto the surrounding li element.
            });
        }
    };

    launcher.createYUIMenu = function(target){
        target = target || 'actionsMenu';
        var containerMenu = new YAHOO.widget.Menu('containerMenu', { autosubmenudisplay:true, scrollincrement:5, position:'static' });
        containerMenu.addItems(containerMenuItems);
        if (containerMenuItems[0].submenu.itemdata.length > 0) {
            containerMenu.render(target);
        }
    };

    launcher.init = function() {
        // populate or hide the command launcher based on what's in context
        if (!projectId || !xsiType) {
            // Not on an xnat object's page (likely being used in a plugin page), skip this
            return;
        }

        XNAT.xhr.getJSON({
            url: rootUrl('/xapi/commands/available?project=' + projectId + '&xsiType=' + xsiType),
            success: function (data) {
                var availableCommands = data;
                if (!availableCommands.length) {
                    return false;
                } else {
                    var spawnedCommands = [];
                    availableCommands.forEach(function (command) {
                        launcher.addYUIMenuItem(command);
                    });
                    launcher.createYUIMenu('actionsMenu',spawnedCommands);
                }

            },
            fail: function (e) {
                errorHandler(e);
            }
        });

        // Special case: If this is a session, run a second context check for scans
        // only support scan-level actions if the new scan table is found.
        if (XNAT.data.context.isImageSession && document.getElementById('selectable-table-scans')) {
            var xsiScanType = xsiType.replace('Session','Scan');

            XNAT.xhr.getJSON({
                url: rootUrl('/xapi/commands/available?project=' + projectId + '&xsiType=' + xsiScanType),
                success: function (data) {
                    var availableCommands = data;
                    if (!availableCommands.length) {
                        return false;
                    } else {
                        // build menu of commands
                        var spawnedCommands = [];
                        availableCommands.forEach(function (command) {
                            command.launcher = 'multiple-scans';
                            command.uri = '';
                            launcher.addMenuItem(command,spawnedCommands);
                        });

                        // add action menu to each scan listing
                        launcher.scanList = XNAT.data.context.scans || [];
                        launcher.scanList.forEach(function(scan){
                            var scanCommands = [];
                            availableCommands.forEach(function (command) {
                                command.launcher = 'single-scan';
                                command.uri = fullScanPath(scan['id']);
                                launcher.addMenuItem(command,scanCommands);
                            });

                            if (scanCommands.length > 0){
                                var scanActionTarget = $('tr#scan-'+scan['id']).find('.single-scan-actions-menu');
                                scanActionTarget.append(scanCommands)
                                $('.run-menu').show();
                            }
                        });

                        if (spawnedCommands.length > 0) {
                            // add commands to Bulk Run action menu at the top of the scan table
                            var menuTarget = $('#scanActionsMenu');
                            launcher.createMenu(menuTarget,spawnedCommands);
                            $('.scan-actions-controls').show();
                            $('#scanTable-run-containers').removeClass('hidden');
                        }
                    }
                },
                fail: function(e) {
                    errorHandler(e);
                }
            });
        }

    };

    launcher.open = window.openCommandLauncher = function(obj){
        var launcher = obj.launcher,
            wrapperId = obj.wrapperid,
            rootElement = obj.rootElement,
            rootElementValue = obj.rootElementValue || undefined;

        switch(launcher) {
            case 'select-scan':
                XNAT.dialog.message({ title: 'Method Not Supported', content: 'Sorry, the "select-scan" method of launching multiple container is no longer supported. Use the "multiple-scans" method instead.' });
                break;
            case 'single-scan':
                var rootElementPath = obj.uri;
                XNAT.plugin.containerService.launcher.singleScanDialog(wrapperId,rootElementPath);
                break;
            case 'multiple-scans':
                var listOfScanIds = [];
                $('.selectable').find('tbody').find('input:checked').each(function(){
                    var scanId = $(this).val();
                    var scanPath = fullScanPath(scanId);
                    listOfScanIds.push(scanPath);
                });
                XNAT.plugin.containerService.launcher.bulkLaunchDialog(wrapperId,'scan',listOfScanIds);
                break;
            default:
                XNAT.plugin.containerService.launcher.defaultLauncher(wrapperId,rootElement,rootElementValue);
        }
    };

    $(document).on('click','.commandLauncher',function(){
        var launcherObj = $(this).data();
        launcher.open(launcherObj);
    });

    $(document).on('click','.advanced-settings-toggle',function(){
        var advancedPanel = $(this).parent('.advanced-settings-container').find('.advanced-settings');
        if ($(this).hasClass('active')) {
            $(this).removeClass('active');
            advancedPanel.slideUp(300);
        } else {
            $(this).addClass('active');
            advancedPanel.slideDown(300);
        }
    });

    launcher.refresh = function(){
        launcherMenu.html('');
        launcher.init();
    };

    $(document).ready(function(){
        launcher.init();
    });

}));
