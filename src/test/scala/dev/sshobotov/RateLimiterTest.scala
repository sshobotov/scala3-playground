package dev.sshobotov

import scala.concurrent.{blocking, ExecutionContext, Future}
import scala.util.Failure
import util.Syntax._

class RateLimiterTest extends util.NamedFunSuite {
  given ExecutionContext = ExecutionContext.global

  def returnBlocking(value: Int) = Future {
    blocking { Thread.sleep(50); value }
  }

  test("run passed requests if limit wasn't reached") {
    val limiter = RateLimiter.inFlight[String, Int](5)

    Future.sequence(List(
      limiter.proxify("test", returnBlocking(5)),
      limiter.proxify("test", returnBlocking(10))
    )).map {
      assertEquals(_, List(5, 10))
    }
  }

  test("fail requests reached configured limit") {
    val limiter = RateLimiter.inFlight[String, Int](1)

    Future.sequence(List(
      limiter.proxify("test", returnBlocking(5)).attempt,
      limiter.proxify("test", returnBlocking(10)).attempt
    )).map { results =>
      assertEquals(results.count(_.isSuccess), 1)
      assertEquals(results.collect { case Failure(e: RateLimiter.LimitExceeded) => true }.size, 1)
    }
  }

  test("run passed requests for different keys with independent limits") {
    val limiter = RateLimiter.inFlight[String, Int](1)

    Future.sequence(List(
      limiter.proxify("test1", returnBlocking(5)),
      limiter.proxify("test2", returnBlocking(10))
    )).map {
      assertEquals(_, List(5, 10))
    }
  }
}
