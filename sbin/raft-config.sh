#!/usr/bin/env bash

if [ -z "$RAFT_HOME" ]; then
    export RAFT_HOME="$(cd "`dirname "$0"`"/..; pwd)"
fi

export RAFT_CONF_DIR="${RAFT_CONF_DIR:-"${RAFT_HOME}/conf"}"

if [ "$RAFT_PID_DIR" = "" ]; then
    export RAFT_PID_DIR="/tmp"
fi

# - RAFT_HOME, root of the project
# - RAFT_CONF, configuration file

# - RAFT_LOCAL_IP, host of node

# - RAFT_INITIATOR_HOST, host of raft initiator
# - RAFT_INITIATOR_PORT, port of raft initiator

# - RAFT_LAUNCHER_HOST, port of raft server launcher
# - RAFT_LAUNCHER_PORT, port of raft server launcher