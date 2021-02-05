package dev.sshobotov

import java.util.NoSuchElementException
import java.util.concurrent.locks.ReentrantReadWriteLock

import scala.concurrent.Future
import scala.math.Ordered._

/**
 * @param strategy         algorithm how to distribute requests between Providers
 * @param registryCap      maximum number of Providers alllowed to register, minimum=1 default=10
 * @param heartBeatCheck   Providers' liveness check, optional
 * @param requestRateLimit incomming requests 
 * 
 * Example:
 * {{{
 * import java.util.Timer
 * import scala.concurrent.duration._
 * 
 * val tm = new Timer("heartbeeat-timer")
 * val hb = HeartBeatCheck.thresholding(new HeartBeatCheck.TimingBeat(tm, 2.seconds))
 * val lb = new LoadBalancer(new RoundRobinBalancing, heartBeatCheck = Some(hb), requestRateLimit = Some(RateLimiter.inFlight(100)))
 * }}}
 */
class LoadBalancer(
  strategy: BalancingStrategy[Provider],
  registryCap: Int = 10,
  heartBeatCheck: Option[HeartBeatCheck[Provider]]        = None,
  requestRateLimit: Option[RateLimiter[Provider, String]] = None
) {
  assert(registryCap > 0, "registryCap should be > 0")

  private var registry = Set.empty[Provider]
  private var enabled  = Set.empty[Provider]
  private val lock     = new ReentrantReadWriteLock

  private val _get = requestRateLimit match {
    case Some(limiter) => (provider: Provider) => limiter.proxify(provider, provider.get())
    case _             => (provider: Provider) => provider.get()
  }

  strategy.feed(enabled)
  heartBeatCheck.foreach(_.start(
    collectChecklist,
    handleCheckResult
  ))

  /**
   * Delegates get() to of registered providers,
   * will return NoSuchElementException if no enabled/registered providers
   */
  def get(): Future[String] =
    strategy.pick() match {
      case Some(picked) => _get(picked)
      case _            => Future.failed(new NoSuchElementException("No enabled provider found"))
    }

  /**
   * Registers new providers,
   * returns false if capacity reached and provider can't be registered 
   */
  def register(provider: Provider): Boolean =
    writeLock {
      if (registry.size < registryCap) {
        if (!registry.contains(provider)) {
          registry = registry + provider
          enabled = enabled + provider
          
          strategy.feed(enabled)
        }
        true
      } else {
        false
      }
    }

  /**
   * Excludes Provider from enabled providers,
   * will return false if such provider was never registered
   */
  def exclude(provider: Provider): Boolean =
    writeLock {
      val wasRegistered = registry.contains(provider)
      if (enabled.contains(provider)) {
        enabled = enabled - provider

        strategy.feed(enabled)
      }
      wasRegistered
    }

  /**
   * Includes previously excluded Provider into enabled providers,
   * will return false if such provider was never registered
   */
  def include(provider: Provider): Boolean =
    writeLock {
      val wasRegistered = registry.contains(provider)
      if (wasRegistered && !enabled.contains(provider)) {
        enabled = enabled + provider

        strategy.feed(enabled)
      }
      wasRegistered
    }

  private def collectChecklist(): HeartBeatCheck.Checklist[Provider] = {
    lock.readLock().lock()
    try {
      registry.map { p => (p, () => p.check()) }.toMap
    } finally {
      lock.readLock().unlock()
    }
  }

  private def handleCheckResult(provider: Provider, passed: Boolean) =
    if (passed) include(provider) else exclude(provider)

  private def writeLock[T](body: => T): T = {
    lock.writeLock().lock()
    try { body } finally { lock.writeLock().unlock() }
  }
}
