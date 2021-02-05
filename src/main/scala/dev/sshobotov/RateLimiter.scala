package dev.sshobotov

import java.util.concurrent.ConcurrentHashMap

import scala.concurrent.{ExecutionContext, Future}
import scala.util.control.NoStackTrace

/**
 * Limits execution of underlying requests based on grouping key (might be the same for all reuests)
 */
abstract class RateLimiter[K, T] {
  
  /**
   * @param key     grouping key, allows limit different groups of requests in a individual way
   * @param request requuest to run, lazy
   */
  def proxify(key: K, request: => Future[T]): Future[T]
}

object RateLimiter {

  final case class LimitExceeded(value: Int) extends
    Exception(s"Rate limit exceeded $value, please try later") with NoStackTrace

  /**
   * Provides implementation to limit requests in-flight per key,
   * will fail a resulti future with [[LimitExceeded]] exception if limit was reached
   * 
   * @param perKeyLimit  requests in-flight per key limit, more in-flight requests will be failed
   * @param asyncContext non-blocling ExecutionContext to forward Future results, default=global
   */
  def inFlight[K, T](perKeyLimit: Int, asyncContext: ExecutionContext = ExecutionContext.global): RateLimiter[K, T] = {
    assert(perKeyLimit > 0, "perKeyLimit shoulld be > 0")

    new RateLimiter[K, T] {
      given ExecutionContext = asyncContext

      private val inFlights = new ConcurrentHashMap[K, Int]

      override def proxify(key: K, request: => Future[T]): Future[T] = {
        val computed = inFlights.compute(key, (_, v) => Option(v).getOrElse(1) + 1)
        if (computed > perKeyLimit)
          Future.failed(LimitExceeded(perKeyLimit))
        else {
          request.onComplete(_ => inFlights.compute(key, (_, v) => v - 1))
          request
        }
      }
    }
  }
}
