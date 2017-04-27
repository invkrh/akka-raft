# akka-raft
An implementation of raft consensus algorithm based on akka

## Lesson learned:

### Stop actor
Both stop and PoisonPill will terminate the actor and stop the message queue.
They will cause the actor to cease processing messages, send a stop call to all
its children, wait for them to terminate, then call its postStop hook.
All further messages are sent to the dead letters mailbox.

The difference is in which messages get processed before this sequence starts.
In the case of the `stop` call, the message currently being processed is completed first,
with all others discarded. When sending a PoisonPill, this is simply another message
in the queue, so the sequence will start when the PoisonPill is received.
All messages that are ahead of it in the queue will be processed first.

### Ask pattern ActorRef
Actor path under /temp is the guardian for all short-lived system-created actors,