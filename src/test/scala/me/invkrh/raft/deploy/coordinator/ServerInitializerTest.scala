package me.invkrh.raft.deploy.coordinator

import akka.testkit.TestProbe
import me.invkrh.raft.RaftTestHarness
import me.invkrh.raft.deploy.{Ready, RemoteProvider}
import me.invkrh.raft.server.Message.Init
import scala.concurrent.duration._

class ServerInitializerTest extends RaftTestHarness("ServerInitializerTest") {}
