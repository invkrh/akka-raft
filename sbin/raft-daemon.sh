#!/usr/bin/env bash

usage="Usage: raft-daemon.sh (init|join|stop) <main-class> <instance-number> <args..>"

# if no args specified, show usage
if [ $# -lt 1 ]; then
    echo $usage
    exit 1
fi

if [ -z "${RAFT_HOME}" ]; then
    export RAFT_HOME="$(cd "`dirname "$0"`"/..; pwd)"
fi

export RAFT_CONF_DIR="${RAFT_CONF_DIR:-"${RAFT_HOME}/conf"}"

option=$1
shift
class=$1
shift
instance=$1
shift

if [ "$SPARK_IDENT_STRING" = "" ]; then
    export RAFT_IDENT_STRING="${USER/./}"
fi

# get log directory
if [ "$RAFT_LOG_DIR" = "" ]; then
    export RAFT_LOG_DIR="${RAFT_HOME}/logs"
fi
mkdir -p "$RAFT_LOG_DIR"
touch "$RAFT_LOG_DIR"/.spark_test > /dev/null 2>&1
TEST_LOG_DIR=$?
if [ "${TEST_LOG_DIR}" = "0" ]; then
    rm -f "$RAFT_LOG_DIR"/.spark_test
else
    chown "$RAFT_IDENT_STRING" "$RAFT_LOG_DIR"
fi

# get pid directory
if [ "$RAFT_PID_DIR" = "" ]; then
    RAFT_PID_DIR=/tmp
fi

# local variable
log="$RAFT_LOG_DIR/raft-$RAFT_IDENT_STRING-$class-$instance.out"
pid="$RAFT_PID_DIR/raft-$RAFT_IDENT_STRING-$class-$instance.pid"

function raft_rotate_log () {
    log=$1;
    num=5;
    if [ -n "$2" ]; then
	    num=$2
    fi
    if [ -f "$log" ]; then # rotate logs
        while [ $num -gt 1 ]; do
            prev=`expr $num - 1`
            [ -f "$log.$prev" ] && mv "$log.$prev" "$log.$num"
            num=$prev
        done
        mv "$log" "$log.$num";
    fi
}

function init() {
    java -cp $RAFT_HOME/build/akka-raft-assembly-0.1.0.jar $@
}

function join() {
    mkdir -p "$RAFT_PID_DIR"

    if [ -f "$pid" ]; then
        TARGET_ID="$(cat "$pid")"
        if [[ $(ps -p "$TARGET_ID" -o comm=) =~ "java" ]]; then
            echo "$class running as process $TARGET_ID.  Stop it first."
            exit 1
        fi
    fi

    raft_rotate_log "$log"
    echo "starting $class, logging to $log"

    command="java -cp $RAFT_HOME/build/akka-raft-assembly-0.1.0.jar $@"
    nohup $command >> $log 2>&1 < /dev/null &
    newpid="$!"

    echo "$newpid" > "$pid"

    # Poll for up to 5 seconds for the java process to start
    for i in {1..10}
    do
        if [[ $(ps -p "$newpid" -o comm=) =~ "java" ]]; then
            break
        fi
        sleep 0.5
    done

    sleep 2
    # Check if the process has died; in that case we'll tail the log so the user can see
    if [[ ! $(ps -p "$newpid" -o comm=) =~ "java" ]]; then
        echo "failed to launch: $@"
        tail -2 "$log" | sed 's/^/  /'
        echo "full log in $log"
    fi
}

case $option in

    (init)
        init $class $@
        ;;

    (join)
        join $class $@
        ;;

    (stop)
        if [ -f $pid ]; then
            TARGET_ID="$(cat "$pid")"
            if [[ $(ps -p "$TARGET_ID" -o comm=) =~ "java" ]]; then
                echo "stopping $class"
                kill "$TARGET_ID" && rm -f "$pid"
            else
                echo "no $class to stop"
            fi
        else
          echo "no $class to stop"
        fi
        ;;

    (status)
        if [ -f $pid ]; then
            TARGET_ID="$(cat "$pid")"
            if [[ $(ps -p "$TARGET_ID" -o comm=) =~ "java" ]]; then
                echo $class is running.
                exit 0
            else
                echo $pid file is present but $class not running
                exit 1
            fi
        else
            echo $class not running.
            exit 2
        fi
        ;;

    (*)
        echo $usage
        exit 1
        ;;

esac

