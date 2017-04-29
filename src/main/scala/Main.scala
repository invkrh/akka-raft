//package head_thrash
//
//import scala.concurrent.ExecutionContext.Implicits.global
//
//import akka.actor._
//import akka.util.Timeout
//import scala.concurrent.{Await, Future}
//import scala.concurrent.duration._
//import scala.util.{Failure, Success, Try}
//
//object Main extends App {
//
//  val system = ActorSystem("actors")
//  val actor1 = system.actorOf(Props[MyActor], "node_1")
//  val actor2 = system.actorOf(Props[MyActor], "node_2")
//
//  actor1 ! actor2
//  Thread.sleep(2000)
//  system.stop(actor1)
//}
//
//class MyActor extends Actor with ActorLogging {
//  import akka.pattern.ask
//
//  implicit val timeout = Timeout(100.days)
//
//  var act: ActorRef = _
//
//  val cancellable = this.context.system.scheduler.schedule(Duration.Zero, 100 milliseconds)(sayHello())
//  def say(x: String) = println(x)
//
//  def futureToFutureTry[T](f: Future[T]): Future[Try[T]] =
//    f.map(Success(_))
//      .recover { case e => Failure(e) }
//
//  def sayHello(): Unit = {
//    if (act != null) {
//      futureToFutureTry((act ? "hi").mapTo[String]).onComplete{
//        case Success(x) => say(x.toString)
//        case Failure(e) => println(e)
//      }
//    }
//  }
//
//  override def postStop(): Unit = {
//
//    cancellable.cancel()
//    println("stop")
//  }
//  def receive = {
//    case who: ActorRef => act = who
//    case "hi" => sender() ! "hello"
//  }
//}
