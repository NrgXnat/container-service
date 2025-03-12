console.log('containerService-siteAdmin-assignProject.js');

var XNAT = getObject(XNAT || {});
XNAT.plugin = getObject(XNAT.plugin || {});
XNAT.plugin.containerService = getObject(XNAT.plugin.containerService || {});

(function(factory) {
    if (typeof define === 'function' && define.amd) {
        define(factory);
    } else if (typeof exports === 'object') {
        module.exports = factory();
    } else {
        return factory();
    }
}(function() {


   XNAT.plugin.containerService.assignProjectlauncher = assignProjectlauncher = getObject(XNAT.plugin.containerService.assignProjectlauncher || {});

    var projectDataTypeSingularName = XNAT.app.displayNames.singular.project;
    var projectDataTypePluralName = XNAT.app.displayNames.plural.project;
    var projectDataTypePluralNameLC = projectDataTypePluralName.toLowerCase();
    var projectsList = [];
    var projectsUrl = XNAT.url.restUrl('xapi/role/projects',{},false,false);


    assignProjectlauncher.updateCommandAssociations = function(commandId, wrapperName, enabledFlag, projectArray) {
        projectArray.forEach((project) => {
            var urlToSubmit = XNAT.url.restUrl('xapi/projects/'+ project + '/commands/' + commandId +'/wrappers/'+ wrapperName +'/'+ enabledFlag +'?reason=Admin');
            XNAT.xhr.put({
                url: urlToSubmit,
                async: true,
                success: function() {
                    xmodal.closeAll();
                    XNAT.ui.banner.top(2000, wrapperName + ' for ' + projectDataTypeSingularName +  '  successfully ' + enabledFlag, 'success');
                },
                fail: function(e) {
                    errorHandler(e, 'Could not ' + enabledFlag + ' command for ' + project , false);
                }
            });
         });
    }

        assignProjectlauncher.fetchAssignedProjects = function(wrapperId){
            var assignedProjectsUrl = XNAT.url.restUrl('xapi/wrappers/'+wrapperId +'/projects',{},false,false);
            var assignedProjects = [];
            XNAT.xhr.get({
                url: assignedProjectsUrl,
                async: false,
                dataType: 'json',
                success: function(data) {
                    assignedProjects = data;
                },
                error: function(e) {
                    errorHandler(e);
                }
            });
            return assignedProjects;
        }


    assignProjectlauncher.populateForm = function($form, projectsAlreadyAssigned) {
        if (typeof assignProjectlauncher.$table != 'undefined') {
           // projectsList = [];
            assignProjectlauncher.$table.remove();
        }

        let columnIds = ["assign", "name", "id"];
        let labelMap = {
            assign: {
                label: "Select",
                checkboxes: true,
                id: "Assign"
            },
            name: {
                label: projectDataTypeSingularName + " Name",
                checkboxes: false,
                id: projectDataTypeSingularName + " Name"
            },
            id: {
                label: projectDataTypeSingularName + " ID",
                checkboxes: false,
                id: projectDataTypeSingularName + " ID"
            },
            investigator: {
                label: "Primary Investigator",
                checkboxes: false,
                id: "Primary Investigator"
            }
        };

        var projectsTable = XNAT.table({
            className: 'projects-table xnat-table data-table fixed-header clean',
            style: {
                width: 'auto'
            }
        });
        var $dataRows = [];
        var dataRows = [];

        function cacheRows() {
            if ($dataRows.length === 0 || $dataRows.length !== dataRows.length) {
                $dataRows = dataRows.length ?
                    $(dataRows) :
                    assignProjectlauncher.container.find('.table-body').find('tr');
            }
            return $dataRows;
        }

        function filterRows(val, name) {
            if (!val) {
                return false
            }
            val = val.toLowerCase();
            var filterClass = 'filter-' + name;
            // cache the rows if not cached yet
            cacheRows();
            $dataRows.addClass(filterClass).filter(function() {
                return $(this).find('td.' + name).containsNC(val).length
            }).removeClass(filterClass);
            assignProjectlauncher.$table.find('.selectable-select-all').each(function() {
                setIndeterminate($(this), $(this).data('id'), $(this).prop('checked'));
            });
        }

        function selectProjectCheckbox(projectId, checked) {
            var ckbox = spawn('input', {
                type: 'checkbox',
                checked: checked,
                disabled: false,
                value: projectId,
                id: 'assign-' + projectId,
                classes: projectId
            });

            return spawn('div.center', [ckbox]);
        }


        projectsTable.thead().tr();
        $.each(columnIds, function(i, c) {
            projectsTable.th('<b>' + labelMap[c].label + '</b>');
        });
        // add check-all header row
        projectsTable.tr({
            classes: 'filter'
        });

        const projectSelectCheckBox = spawn('input.project-select', {
            type: 'checkbox',
            name: 'project-select',
            checked: false,
            onclick: function () {
                if ($(this).is(':checked')) {
                    markChecked(true);
                } else {
                    markChecked(false);
                }
            }
        });

        function markChecked(checkedStatus) {
            $.each(projectsList, function(i, project) {
                let checkBoxElt = document.getElementById('assign-' + project.id);
                checkBoxElt.checked = checkedStatus;
            });
        }

        $.each(columnIds, function(i, c) {
            if (labelMap[c].checkboxes) {
                projectsTable.td("", spawn('div.center', [projectSelectCheckBox]));
            } else {
                document.head.appendChild(spawn('style|type=text/css', 'tr.filter-' + c + '{display:none;}'));
                var $filterInput = $.spawn('input.filter-data', {
                    type: 'text',
                    title: c + ':filter',
                    placeholder: 'Filter by ' + c,
                    style: 'width: 90%;'
                });
                $filterInput.on('focus', function() {
                    $(this).select();
                    cacheRows();
                });
                $filterInput.on('keyup', function(e) {
                    var val = this.value;
                    var key = e.which;
                    // don't do anything on 'tab' keyup
                    if (key == 9) return false;
                    if (key == 27) { // key 27 = 'esc'
                        this.value = val = '';
                    }
                    if (!val || key == 8) {
                        $dataRows.removeClass('filter-' + c);
                    }
                    if (!val) {
                        // no value, no filter
                        return false;
                    }
                    filterRows(val, c);
                });
                projectsTable.td({
                    classes: 'filter'
                }, $filterInput[0]);
            }
        });
        projectsTable.tbody({
            classes: 'table-body'
        });

        $.each(projectsList, function(i, e) {
            projectsTable.tr();
            projectsTable.td([selectProjectCheckbox(e.id, projectsAlreadyAssigned.includes(e.id))]);
            projectsTable.td({
                classes: columnIds[1],
            }, e.name);
            projectsTable.td({
                classes: columnIds[2]
            }, e.id);
        });
        $form.empty().prepend(projectsTable.table);
        assignProjectlauncher.container = $form;
        assignProjectlauncher.$table = $(projectsTable.table);

    }

    assignProjectlauncher.assignProject = function(command, wrapper, title) {
        var commandId = command.id,
            wrapperName = wrapper.name;

        let wrapperId = wrapper.id;
        let projectSelectorContent = spawn('div.panel', [spawn('p', 'Please select ' + projectDataTypePluralName)])
        
        let selectedProjects = [];
        var projectsAlreadyAssigned = [];

        var modal = {
            title: 'Assign ' + projectDataTypePluralName + ' for container ' + title,
            content: projectSelectorContent,
            width: 550,
            scroll: true,
            beforeShow: function(obj) {
                xmodal.loading.open({
                    title: 'Fetching assigned ' + projectDataTypePluralName
                });
                projectsAlreadyAssigned = assignProjectlauncher.fetchAssignedProjects(wrapperId);
                let $panel = obj.$modal.find('.panel');
                assignProjectlauncher.populateForm($panel, projectsAlreadyAssigned);
            },
            afterShow: function(obj) {
                xmodal.loading.close();
                if (projectsList.length === 0) {
                    obj.close();
                    xmodal.alert({
                        content: '<p><strong>There are no ' + projectDataTypePluralName + ' left to include.</strong></p>',
                        okAction: function() {
                            xmodal.closeAll();
                        }
                    });
                }
            },
            buttons: [{
                label: 'Save',
                isDefault: true,
                close: true,
                action: function(obj) {
                    let $panel = obj.$modal.find('.panel'),
                        targetData = {};
                    var additions, subtractions;
                    var oneChecked = false;
                    $.each(projectsList, function(i, project) {
                        var checkBoxElt = document.getElementById('assign-' + project.id);
                        if (checkBoxElt.checked) {
                            selectedProjects.push(project.id);
                        } else {
                            oneChecked = true;
                        }
                    });
                    if (projectsAlreadyAssigned.length === 0) {
                        additions = selectedProjects;
                        subtractions = [];
                    }else {
                        additions = selectedProjects.filter(x => !projectsAlreadyAssigned.includes(x));
                        subtractions = projectsAlreadyAssigned.filter(x => !selectedProjects.includes(x));
                    }
                    if (additions.length === 0 && subtractions.length === 0) {
                        xmodal.alert({
                            title: 'Select ' + projectDataTypeSingularName,
                            content: '<p><strong>Please select at least one ' + projectDataTypeSingularName + ' to add or remove</strong></p>',
                            okAction: function() {
                                xmodal.closeAll();
                            }
                        });
                    }else {
                        //Save the current rows contents into the new path for the selected project
                        if (additions.length > 0) {
                            assignProjectlauncher.updateCommandAssociations(commandId, wrapperName, 'enabled', additions);
                        }
                        if (subtractions.length > 0) {
                            assignProjectlauncher.updateCommandAssociations(commandId, wrapperName, 'disabled', subtractions);
                        }
                    }

                }
            },
            {
                label: 'Cancel',
                isDefault: false,
                close: true
            }
            ]
        };
        XNAT.ui.dialog.open(modal);
    }

    assignProjectlauncher.init = function(){
       //fetch all available projects on site
            XNAT.xhr.get({
                url: projectsUrl,
                async: false,
                dataType: 'json',
                success: function(data) {
                    projectsList = data.sort((a,b) => { return (a.name < b.name) ? -1 : 1});
                },
                error: function(e) {
                    errorHandler(e);
                }
            });
    }


}));



