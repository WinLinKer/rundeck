#!/bin/bash

#Usage: 
#    util-run.sh <project> [URL] commands...

errorMsg() {
   echo "$*" 1>&2
}

DIR=$(cd `dirname $0` && pwd)

proj=$1
if [ "" == "$1" ] ; then
    proj="test"
fi
shift
# accept url argument on commandline, if '-' use default
url="$1"
if [ "-" == "$1" ] ; then
    url='http://localhost:4440'
fi
shift
apiurl="${url}/api"
VERSHEADER="X-RUNDECK-API-VERSION: 1.2"

# curl opts to use a cookie jar, and follow redirects, showing only errors
CURLOPTS="-s -S -L -c $DIR/cookies -b $DIR/cookies"
CURL="curl $CURLOPTS"

if [ ! -f $DIR/cookies ] ; then 
    # call rundecklogin.sh
    sh $DIR/rundecklogin.sh $url
fi

XMLSTARLET=xml

execargs="$*"
# now submit req
runurl="${apiurl}/run/command"

echo "# Run command: ${execargs}"

params="project=${proj}"

# get listing
$CURL --header "$VERSHEADER" --data-urlencode "exec=${execargs}" ${runurl}?${params} > $DIR/curl.out
if [ 0 != $? ] ; then
    errorMsg "ERROR: failed query request"
    exit 2
fi

#test curl.out for valid xml
$XMLSTARLET val -w $DIR/curl.out > /dev/null 2>&1
if [ 0 != $? ] ; then
    errorMsg "ERROR: Response was not valid xml"
    exit 2
fi

#test for expected /joblist element
$XMLSTARLET el $DIR/curl.out | grep -e '^result' -q
if [ 0 != $? ] ; then
    errorMsg "ERROR: Response did not contain expected result"
    exit 2
fi

# job list query doesn't wrap result in common result wrapper
#If <result error="true"> then an error occured.
waserror=$($XMLSTARLET sel -T -t -v "/result/@error" $DIR/curl.out)
if [ "true" == "$waserror" ] ; then
    errorMsg "Server reported an error: "
    $XMLSTARLET sel -T -t -m "/result/error/message" -v "." -n  $DIR/curl.out
    exit 2
else
    $XMLSTARLET sel -T -t -v "/result/success/message" -n  $DIR/curl.out
    $XMLSTARLET sel -T -t -o "Execution started with ID: " -v "/result/execution/@id" -n  $DIR/curl.out
fi

rm $DIR/curl.out
