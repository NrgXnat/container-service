<!-- id: 53674019 -->

**Command resolution** is the process the process by which a command (and usually also a wrapper) definition is combined with runtime values supplied by a user for the purpose of determining everything needed to launch a container. It is a long and intricate process, by far the longest piece of code in the Container Service, but hopefully this document will help you understand it.

## Overview
This document will go into more detail on each stage in the process of resolving the command, but first an overview.

The first stage of resolution is the most complex: resolving the lists of command and wrapper inputs. Since the input values are used throughout the rest of the command and wrapper, once the inputs are resolved the rest of the command's properties are resolved relatively straightforwardly. These include the outputs, the mounts, and the command-line string among others.

## Resolved Input Data Structures



### Pre-resolved input trees

The command and wrapper inputs are defined as a list. Or, more precisely, as three lists: command inputs, external wrapper inputs, and derived wrapper inputs. But those inputs have properties that can define relationships between each other. namely, each derived wrapper input is derived from some other wrapper input, and each wrapper input can provide a value for a command input.

Before we resolve the input values, we go through all the input definitions and generate a list of pre-resolved input trees based on those relationships. At the root of each tree is a command input or external wrapper input, the value of which will (when it is time later to resolve the values) either come in from the user or will be left as default. Each node in each tree contains a list pointing to all its children, those tree nodes who will receive values from this node.

### Resolved input trees

The pre-resolved input trees are then resolved using the input values supplied from the user. The result of this process is a resolved input tree.

Resolving the input tree is done recursively. Starting from a root node of a pre-resolved input tree, we resolve that node into one or more resolved values. Then for each potential value, we resolve each of the node's children. This recurses down to the leaf nodes, which have no children.

The pre-resolved input tree has a fairly simple structure: each pre-resolved input tree node has a list of its child pre-resolved input tree nodes. The resolved input tree, however, is fundamentally different. Each resolved input could potentially have multiple values, and the resolved children will all be different depending on the different values of their parents.

Let's see an example of this. Say we have a command wrapper with these inputs.

    external wrapper inputs: [
        {
            "name": "session",
            "type": "Session"
        }
    ],
    derived wrapper inputs: [
        {
            "name": "scan",
            "type": "Scan",
            "derived-from-wrapper-input": "session"
        },
        {
            "name": "scan-resource",
            "type": "Resource",
            "derived-from-wrapper-input": "scan"
        }
    ]

The pre-resolved input tree that is generated from these inputs looks something like this.

    {
        "input": {session input object...},
        "children": [
            {
                "input": {scan input object...},
                "children": [
                    {
                        "input": {scan-resource input object...},
                        "children": []
                    }
                ]
            }
        ]
    }

This is isomorphic to the list of inputs, in the sense that they encode the same information.

Now let's say the user wants to resolve this on a session that looks something like this:

    session 123
        scan 1
            resource DICOM
            resource NIFTI
        scan 2
            resource DICOM
            resource NIFTI

The `session` input is resolved by instantiating the XNAT Session object and assigning the value. No problem there. But when the `scan` input is resolved, we do not necessarily know which scan is the "correct" one. We may be able to figure that given other information, like matchers or another input value, but we do not know in general that we will always have a unique value. The resolved input tree, then, has to be structured in such a way as to make sense of all the possible values of all the inputs, and allow their children to take on all possible values too.

So we resolve the `scan` input using all possible values that it might have. This, then, extends down to the `scan` input's children, namely `scan-resource`. Since we have multiple possible values for `scan`, and the value of `scan-resource` depends on the value of scan, we must resolve `scan-resource` multiple times, once for each possible value of its parent input `scan`.

The resolved input tree that results would look like this.

    {
        "input": {session input object...},
        "values and children": [
            {
                "value": {XNAT Session object for session 123...},
                "children": []
                    {
                        "input": {scan input object...},
                        "values and children": [
                            {
                                "value": {XNAT Scan object scan 1...}
                                "children": [
                                    {
                                        "input": {scan-resource input object...},
                                        "values and children": [
                                            {
                                                "value": {XNAT resource for scan 1 DICOM}
                                                "children": []
                                            },
                                            {
                                                "value": {XNAT resource for scan 1 NIFTI}
                                                "children": []
                                            }
                                        ]
                                    }
                                ]
                            },
                            {
                                "value": {XNAT Scan object scan 2...}
                                "children": [
                                    {
                                        "input": {scan-resource input object...},
                                        "values and children": [
                                            {
                                                "value": {XNAT resource for scan 2 DICOM}
                                                "children": []
                                            },
                                            {
                                                "value": {XNAT resource for scan 2 NIFTI}
                                                "children": []
                                            }
                                        ]
                                    }
                                ]
                            }
                        ]
                    }
                ]
            }
        ]
    }

### Finding "unique" values from resolved input trees

What do we do with all this complexity of the resolved input trees? Originally, the container service was written to require that all inputs need to eventually be resolved to a unique value. This means that one of the inputs that might have multiple values (for instance, the `scan` input in the example above) needs to have a matcher defined that can distinguish between the values (the `scan` input could have a matcher that checks the scan type), or that the user needs to send an additional parameter at runtime (the user could send the parameter `scan=1`, and that would signal to the container service which scan they want). 

In version 2.0.2, we have added the "multiple" parameter for derived inputs, which slightly relaxes this restriction in that it will flatten resolved multiple values into a list for a derived input that "provides-value-for-command-input" only (a.k.a., it cannot provide files for mount nor be the target of an output handler). This will be useful if you wish to pass, for example, all T1 scans into one FreeSurfer container for averaging. At runtime, though, there is still one (flattened) input value. For more, see the document on [command.json](https://wiki.xnat.org/display/CS/Command#Command-multiple-matches). 

<ac:structured-macro ac:name="tip" ac:schema-version="1" ac:macro-id="9374bcc1-9eb9-457e-a5ee-8786bd50f97a"><ac:rich-text-body>
In the future we may further relax this restriction and allow all the multiplicity of input values. It could be possible to use all of the resolved input values, organize them into a list of distinct sets of values, and launch different containers for each set. We do not allow this now, because there is a lot of complexity to manage, but we could in principle. For more, see the document on [Bulk Launching Containers](https://wiki.xnat.org/display/CS/Bulk+Launching+Containers). This scenario I have outlined here is the `1->N` bulk launch.
</ac:rich-text-body></ac:structured-macro>

Once "unique" values are found, they are exracted from the resolved input tree structure and put into a simple map of input name to input value. More precisely, they are put into two maps: one that stores the raw input value, and another that stores the input value as it would appear in the command line string, which adds any command-line flags (and multiple delimiters, if relevant) that are defined on the input object.

## Resolve Inputs

Now that we know how the resolved input values are stored, how do we actually resolve the values? The process is a little different for each type of input: external wrapper input, derived wrapper input, and command input. We will go through each in turn.

### Resolve external wrapper inputs

This is the process for setting and transforming the resolved value for an external wrapper input.

1. Set the resolved value to the input's default value.
2. Set the resolved value to the value sent in by the user.
3. Resolve any JSONPath strings in the value. (See the section on [resolving JSONPath strings](#resolve-jsonpath-strings).)
4. If the input type is one of the XNAT object types, attempt to instantiate an object using the current resolved value.
    1. If the value starts with `"/"`, attempt to instantiate the object using the value as a URI.
    2. If the value starts with `"{"`, attempt to instantiate the object using the value as a JSON serialized representation of the object.
    3. Apply any input matchers to the object. Reject it if it does not match.
    4. Store the whole object (at least temporarily) and set the resolved value to the object's URL.

### Resolve derived wrapper inputs

This is the process for setting and transforming the resolved value for a derived wrapper input.

1. Get a resolved XNAT object from the parent input. Call this the parent object.
2. If this input has the type "string", use the input's `derived-from-xnat-object-property` value. Pull that property from the parent object.
3. If this input has an XNAT object type, use the known relationships between XNAT object types to derive this input's possible values from the parent object.
    1. If the derived input is "up" the hierarchy from the parent, i.e. the derived input is a project, we can just get the object directly.
    2. If the derived input is "down" the hierarchy from the parent, i.e. parent is a Session and derived is a Scan, we do not just get the child objects directly. It is easier to go a roundabout way.
        1. We first serialize the parent object to JSON.
        2. Then we construct a JSONPath matcher that would match objects of the child type, given any matchers or input values we may have.
        3. Apply the matcher to the parent JSON to get our list of potential matching child JSON objects.
        4. Then deserialize those to get the objects. (See the section on [resolving JSONPath strings](#resolve-jsonpath-strings).)
    3. Store the whole object (at least temporarily) and set the resolved value to the object's URL.

### Resolve command inputs

Compared to the wrapper inputs, resolving command inputs is easy. The command input can come from one of three places, in this order:

1. The input's default value.
2. A runtime value.
3. A value that was provided by a wrapper input.

Once we get a resolved value from one of those sources, we check for and resolve any JSONPath strings we find there. (See the section on [resolving JSONPath strings](#resolve-jsonpath-strings).)

Boolean values get one additional step. Once their value is resolved to `true` or `false`, we set their resolved value to the string set in their `true-value` or `false-value` property, respectively.

### Resolve JSONPath strings

JSONPath is a domain-specific language for searching for and retrieving values out of JSON objects. It was inspired by XPath, which does the same for XML documents. The best reference for the syntax is [the README on the JSONPath source repository](https://github.com/jayway/JsonPath/blob/master/README.md).

In almost any string in the command that gets resolved, you can use a JSONPath string. That JSONPath will be resolved against the command document itself, and replaced with the matching information. To signal to the container service that a string is a JSONPath string, and not just an ordinary string, you must surround it with carets (`^`).

For example, if you need to use the command's name in your command-line string, for instance, you could just use the JSONPath

    "command-line": "echo The command's name is ^$.name^"

In the JSONPath syntax, `$` refers to the root of the JSON object. In our case, that JSON object is the command itself, serialized. If you need to refer to the wrapper, we provide a shorthand way to do that. Use `^wrapper:...^` to surround your JSONPath string. That will signal to the container service that it should use the wrapper's JSON as the root of the search. For example:

    "command-line": "echo The command wrapper's name is ^wrapper:$.name^"

## Resolve Everything Else

After the inputs are fully resolved, the rest falls into place fairly quickly. Note that most strings in the following objects can be resolved not just for JSONPath substrings, but also for input value substrings like `#THIS#`. Each input can set a `replacement-key` property, which defaults to the input's name surrounded by hash marks (`#`). This key can be used in other places in the command, and will be resolved to the input's value.

Some of the "other" properties (environment variables, ports) are simple template replacements. We will not detail that process. The ones below need just a bit of explanation.

###  Outputs

Each output needs to have a value in the `handled-by` property. We look up the wrapper input named in that property to verify it exists, it has a uniquely resolved value, and it is of an XNAT object type that can handle new resources.

The output's `path` and `label` properties can be templates.

### Mounts

To resolve the mounts, first we have to look back at the list of inputs to see which have claimed to provide files to mounts.

For each mount, we first check if an input has claimed to give files to the mount. If none have, then we treat this as an output mount, create a new writable directory for it, and send it on its way. If an input does provide files, we find the `directory` property on the input's XNAT object. That is the host path for the mount. (Unless, of course, the input also has its `via-setup-command` property set. In that case, we quickly detour into resolving the setup command. We set the input's directory to the setup command's input mount, and create a new build directory which serves as both the host path for the setup command's output mount and for this mount.)

### Command-line string

The command-line string is resolved like any other template string in the command. The only difference is that, instead of input keys being replaced by the input's _value_, they get replaced by the input's value combined with its `command-line-flag` and `command-line-separator`, and potentially the `multiple-delimiter`.

