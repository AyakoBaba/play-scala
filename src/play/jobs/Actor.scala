package play.jobs

import play._
import play.exceptions._

import scala.actors.Actor 
import scala.actors.Actor._

object Asyncs{
import scala.actors._
import scala.actors.Futures._
//maybe it needs to be reimplemented to avoid using casting, but the interface is OK
 def awaitForAll[A](timeout:Long,futures:Seq[Future[A]]):Seq[Option[A]]= Futures.awaitAll(timeout,futures:_*).map(_.asInstanceOf[Option[A]])

// def awaitForAll[A,B,C](timeout:Long,futures:(Future[A],Future[B],Future[C])):(Option[A],Option[B],Option[C])= Futures.awaitAll(timeout,futures:_*).map(_.asInstanceOf[Option[A]])
}

object PlayActor extends Actor {
    
    def !!![T](msg: Function0[T]): Future[Either[Throwable,T]] = (PlayActor !! msg).asInstanceOf[Future[Either[Throwable,T]]]
    
    def act {
        loop {
            react {
                case f: Function0[_] => play.Invoker.invokeInThread(new play.Invoker.DirectInvocation() {
                    override def execute {
                        try {
                            sender ! Right(f())
                        } catch {
                            case e => val element = PlayException.getInterestingStrackTraceElement(e)
                                      if (element != null) {
                                          error(
                                              new JavaExecutionException(
                                                  Play.classes.getApplicationClass(element.getClassName()), element.getLineNumber(), e
                                              ),
                                              "Caught in PlayActor"
                                          )
                                      } else {
                                          error(e, "Caught in PlayActor")
                                      }
                                      sender ! Left(e)
                        }                        
                    }
                })
                case _ => sender ! Left(new Exception("Unsupported message type"))
            }   
        }
    }
    
    PlayActor.start

}
