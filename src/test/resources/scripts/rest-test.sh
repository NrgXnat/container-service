#!/bin/bash

user=$1
passw=$2

if [ -z $user ]; then
    echo "Please set your username by running:"
    echo "export user=\"my cool username\""
    exit 1
fi
if [ -z $passw ]; then
    echo "Please set your password by running:"
    echo "export passw=\"my cool password\""
    exit 1
fi

xnat="http://xnat.xnat.org/xapi"

echo "Testing container service rest paths"
echo

echo "Test"
path="/containers/test"
echo GET $path
out=$(curl -sfu $user:$passw $xnat$path)
exitcode=$?
if (($?==22)); then
    echo "failed"
    message=$(sed 's#.*<h1>\(.*\)</h1>.*#\1#' <<< $out)
    if [ -z $message ]; then
        echo $out
    else
        echo $message
    fi
    exit 1
fi
jq <<< $out 2> /dev/null || echo $out
echo

echo "Check server"
path="/containers/server"
echo GET $path
out=$(curl -sfu $user:$passw $xnat$path)
if (($?==22)); then
    echo "failed"
    message=$(sed 's#.*<h1>\(.*\)</h1>.*#\1#' <<< $out)
    if [ -z $message ]; then
        echo $out
    else
        echo $message
    fi
    exit 1
fi
jq <<< $out 2> /dev/null || echo $out
echo

echo "Set server"
path="/containers/server"
host="http://10.0.0.170:2375"
echo POST $path " – host: $host"
out=$(curl -sfu $user:$passw -XPOST -H 'Content-type: application/json' -d '{"host":"'$host'"}' $xnat$path)
exitcode=$?
if (($exitcode==22)); then
    echo "failed"
    message=$(sed 's#.*<h1>\(.*\)</h1>.*#\1#' <<< $out)
    if [ -z $message ]; then
        echo $out
    else
        echo $message
    fi
    exit 1
fi
jq <<< $out 2> /dev/null || echo $out
echo

echo "Check server"
path="/containers/server"
echo GET $path
out=$(curl -sfu $user:$passw $xnat$path)
if (($?==22)); then
    echo "failed"
    message=$(sed 's#.*<h1>\(.*\)</h1>.*#\1#' <<< $out)
    if [ -z $message ]; then
        echo $out
    else
        echo $message
    fi
    exit 1
fi
jq <<< $out 2> /dev/null || echo $out
echo

# echo "Check images"
# path="/containers/images"
# echo GET $path
# out=$(curl -sfu $user:$passw $xnat$path)
# if (($?==22)); then
#     echo "failed"
#     echo $out
#     message=$(sed 's#.*<h1>\(.*\)</h1>.*#\1#' <<< $out)
#     echo $message
#     exit 1
# fi
# jq <<< $out 2> /dev/null || echo $out
# echo
#
