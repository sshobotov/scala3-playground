package dev.sshobotov.util

import scala.concurrent.{Await, ExecutionContext, Future}
import scala.concurrent.duration.FiniteDuration
import scala.util.{Failure, Success, Try}

object Syntax {

  final case class Timeout(limit: FiniteDuration)
  
  extension [T](underlying: Future[T])
    def toTry(using Timeout): Try[T] =
      Try { Await.result(underlying, summon[Timeout].limit) }

    def success(using Timeout): T = toTry.get

    def failure(using Timeout): Throwable = toTry.failed.get

    def attempt(using ExecutionContext): Future[Try[T]] =
      underlying.map(Success(_)).recover { case e => Failure(e) }
}
