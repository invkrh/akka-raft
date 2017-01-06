import akka.actor.{Actor, ActorSystem, Props}
import akka.event.Logging

object Main extends App {

  class MyActor extends Actor {
    val log = Logging(context.system, this)

    def receive = {
      case "test" => log.info("received test")
      case _      => log.info("received unknown message")
    }
  }

  // ActorSystem is a heavy object: create only one per application
  val system = ActorSystem("mySystem")
  val myActor = system.actorOf(Props[MyActor], "myactor")

  myActor ! "test"
  myActor ! "asdf"

  system.terminate()
}
