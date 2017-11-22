package me.invkrh.raft.kit

import scala.concurrent.duration._
import scala.language.postfixOps

import akka.actor.{ActorRef, ActorSystem}
import akka.testkit.TestProbe

import me.invkrh.raft.message.RaftMessage

sealed trait ProbeCheck {
  def execute(server: ActorRef, probes: Seq[TestProbe]): Unit
}

case class Check(action: () => Unit) extends ProbeCheck {
  override def execute(server: ActorRef, probes: Seq[TestProbe]): Unit = {
    action()
  }
}

case class Expect(msg: RaftMessage) extends ProbeCheck {
  override def execute(server: ActorRef, probes: Seq[TestProbe]): Unit = {
    probes foreach { _ expectMsg msg }
  }
}

case class Tell(msg: RaftMessage) extends ProbeCheck {
  override def execute(server: ActorRef, probes: Seq[TestProbe]): Unit = {
    probes foreach (p => server.tell(msg, p.ref))
  }
}

case class Reply(msg: RaftMessage) extends ProbeCheck {
  override def execute(server: ActorRef, probes: Seq[TestProbe]): Unit = {
    probes foreach { _ reply msg }
  }
}

case class MinorReply(msg: RaftMessage, msgForOthers: Option[RaftMessage] = None)
  extends ProbeCheck {
  override def execute(server: ActorRef, probes: Seq[TestProbe]): Unit = {
    val minor = (probes.size + 1) / 2 - 1
    probes.take(1) foreach (_ reply msg)
    msgForOthers match {
      case Some(other) => probes.drop(probes.length - 1) foreach (_ reply other)
      case None =>
    }
  }
}

case class MajorReply(msg: RaftMessage, msgForOthers: Option[RaftMessage] = None)
  extends ProbeCheck {
  override def execute(server: ActorRef, probes: Seq[TestProbe]): Unit = {
    val major = (probes.size + 1) / 2 + 1
    probes.take(major) foreach (_ reply msg)
    msgForOthers match {
      case Some(other) => probes.drop(major) foreach (_ reply other)
      case None =>
    }
  }
}

case class Delay(sleep: FiniteDuration, action: ProbeCheck) extends ProbeCheck {
  override def execute(server: ActorRef, probes: Seq[TestProbe]): Unit = {
    Thread.sleep(sleep.toMillis)
    action.execute(server, probes)
  }
}

case class Within(min: FiniteDuration, max: FiniteDuration, actions: ProbeCheck*)(
    implicit system: ActorSystem)
  extends ProbeCheck {
  val loosenFactor = 0.1
  override def execute(server: ActorRef, probes: Seq[TestProbe]): Unit = {
    val minMS = min.toMillis
    val maxMS = max.toMillis
    val pb = TestProbe()
    pb.within(minMS * (1 - loosenFactor).millis, maxMS * (1 + loosenFactor).millis) {
      actions foreach { _.execute(server, probes) }
    }
  }
}

case class Rep(times: Int, actions: ProbeCheck*) extends ProbeCheck {
  override def execute(server: ActorRef, probes: Seq[TestProbe]): Unit = {
    (0 until times).foreach { _ =>
      actions foreach { _.execute(server, probes) }
    }
  }
}

case class FishForMsg(f: PartialFunction[Any, Boolean]) extends ProbeCheck {
  override def execute(server: ActorRef, probes: Seq[TestProbe]): Unit = {
    probes foreach { _.fishForMessage(10 seconds)(f) }
  }
}
