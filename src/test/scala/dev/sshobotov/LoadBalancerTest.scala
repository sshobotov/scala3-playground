package dev.sshobotov

import java.util.concurrent.atomic.AtomicReference
import java.util.NoSuchElementException

import util.Syntax.{Timeout, given, _}

import scala.concurrent.{Future, ExecutionContext}
import scala.concurrent.duration._
import scala.util.Random

class LoadBalancerTest extends util.NamedFunSuite {
  given ExecutionContext = ExecutionContext.global
  given Timeout          = Timeout(1.second)

  class TestHeartBeatCheck extends HeartBeatCheck[Provider] {
    import HeartBeatCheck._

    private val subscribers = new AtomicReference[List[ResultSubscriber[Provider]]](List.empty)

    override def start(
      checklist: Publisher[Checklist[Provider]],
      notify: ResultSubscriber[Provider]
    ): Cancellation = {
      subscribers.updateAndGet(notify :: _)
      () => ()
    }

    def push(entry: Provider, passed: Boolean): Unit =
      subscribers.get.foreach(cb => cb(entry, passed))
  }

  def randomId = Random.alphanumeric.take(8).mkString

  def lastItemStrategy = new BalancingStrategy[Provider] {
    private val value = new AtomicReference[Option[Provider]](None)

    override def feed(candidates: Set[Provider]): Unit =
      value.set(candidates.toSeq.lastOption)

    override def pick(): Option[Provider] = value.get
  }

  def checkPositiveProvider(_id: String = randomId) = new Provider {
    override val id: String               = _id
    override def check(): Future[Boolean] = Future.successful(true)
  }

  test("delegate get() to underlying Provider") {
    val balancer = new LoadBalancer(lastItemStrategy)
    val provider = checkPositiveProvider()

    balancer.register(provider)
    val result = balancer.get().success

    assertEquals(result, provider.id)
  }

  test("return error for get() invocation if no registered providers") {
    val balancer = new LoadBalancer(lastItemStrategy)

    val reason = balancer.get().failure
    assert(reason.isInstanceOf[NoSuchElementException])
  }

  test("alow to register providers until maximum number of it was reached") {
    val balancer  = new LoadBalancer(lastItemStrategy, 1)
    val provider1 = checkPositiveProvider()
    val provider2 = checkPositiveProvider()

    val added1 = balancer.register(provider1)
    assert(added1)

    val added2 = balancer.register(provider2)
    assert(!added2)

    val result = balancer.get().success
    assertEquals(result, provider1.id)
  }

  test("delegate calls to providers chosen by BalancingStrategy") {
    val balancer  = new LoadBalancer(lastItemStrategy)
    val provider1 = checkPositiveProvider()
    val provider2 = checkPositiveProvider()

    def safeGet = balancer.get().success

    balancer.register(provider1)
    balancer.register(provider2)
    
    assertEquals(safeGet, provider2.id)
    assertEquals(safeGet, provider2.id)
  }

  test("allow exclusion from request delegation of specific registered providers") {
    val balancer  = new LoadBalancer(lastItemStrategy)
    val provider1 = checkPositiveProvider()
    val provider2 = checkPositiveProvider()

    balancer.register(provider1)
    balancer.register(provider2)

    val done = balancer.exclude(provider2)
    assert(done)
    
    val result = balancer.get().success
    assertEquals(result, provider1.id)
  }

  test("not allow exclusion from request delegation of unregistered providers") {
    val balancer  = new LoadBalancer(lastItemStrategy)
    val provider1 = checkPositiveProvider()
    val provider2 = checkPositiveProvider()

    balancer.register(provider1)

    val done = balancer.exclude(provider2)
    assert(!done)
  }

  test("allow exclusion from request delegation of already exclluded providers") {
    val balancer = new LoadBalancer(lastItemStrategy)
    val provider = checkPositiveProvider()

    balancer.register(provider)
    balancer.exclude(provider)

    val done = balancer.exclude(provider)
    assert(done)
  }

  test("return error for get() invocation if all providers were excluded") {
    val balancer  = new LoadBalancer(lastItemStrategy)
    val provider1 = checkPositiveProvider()

    balancer.register(provider1)
    balancer.exclude(provider1)

    val reason = balancer.get().failure
    assert(reason.isInstanceOf[NoSuchElementException])
  }

  test("allow inclusion into request delegation of previously excluded providers") {
    val balancer  = new LoadBalancer(lastItemStrategy)
    val provider1 = checkPositiveProvider()
    val provider2 = checkPositiveProvider()

    balancer.register(provider1)
    balancer.register(provider2)
    balancer.exclude(provider2)

    val done = balancer.include(provider2)
    assert(done)
    
    val result = balancer.get().success
    assertEquals(result, provider2.id)
  }
  
  test("not allow inclusion into request delegation of unregistered providers") {
    val balancer  = new LoadBalancer(lastItemStrategy)
    val provider1 = checkPositiveProvider()
    val provider2 = checkPositiveProvider()

    balancer.register(provider1)

    val done = balancer.include(provider2)
    assert(!done)
  }

  test("allow inclusion into request delegation of not exclluded providers") {
    val balancer = new LoadBalancer(lastItemStrategy)
    val provider = checkPositiveProvider()

    balancer.register(provider)

    val done = balancer.include(provider)
    assert(done)
  }

  test("exclude providers didn't pass heartbeat check") {
    val heartbeat = new TestHeartBeatCheck
    val balancer  = new LoadBalancer(lastItemStrategy, heartBeatCheck = Some(heartbeat))
    val provider1 = checkPositiveProvider()
    val provider2 = checkPositiveProvider()

    balancer.register(provider1)
    balancer.register(provider2)

    heartbeat.push(provider2, passed = false)

    val result = balancer.get().success
    assertEquals(result, provider1.id)
  }

  test("include providers did pass heartbeat check") {
    val heartbeat = new TestHeartBeatCheck
    val balancer  = new LoadBalancer(lastItemStrategy, heartBeatCheck = Some(heartbeat))
    val provider1 = checkPositiveProvider()
    val provider2 = checkPositiveProvider()

    balancer.register(provider1)
    balancer.register(provider2)
    balancer.exclude(provider2)

    heartbeat.push(provider2, passed = true)

    val result = balancer.get().success
    assertEquals(result, provider2.id)
  }

  test("propagate RateLimiter error instead of request execution") {
    val error     = new RuntimeException("you shall not pass")
    val rateLimit = new RateLimiter[Provider, String] {
      override def proxify(key: Provider, request: => Future[String]): Future[String] = Future.failed(error)
    }

    val balancer = new LoadBalancer(lastItemStrategy, requestRateLimit = Some(rateLimit))
    val provider = checkPositiveProvider()

    balancer.register(provider)

    val result = balancer.get().failure
    assertEquals(result, error)
  }
}
