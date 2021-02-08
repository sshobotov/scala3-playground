package dev.sshobotov

import java.util.Timer

import scala.concurrent.{blocking, ExecutionContext, Future}
import scala.concurrent.duration._
import scala.util.{Success, Random}

import util.Syntax._

class IntegrationTest extends munit.FunSuite {
  given ExecutionContext = ExecutionContext.global

  def randomId = Random.alphanumeric.take(8).mkString

  def blockingProvider() = new Provider {
    override val id      = randomId
    override def get()   = Future(blocking { Thread.sleep(100); id })
    override def check() = Future(blocking { Thread.sleep(100); true })
  }

  def provide(tm: Timer, perProviderLimit: Int = 50): LoadBalancer = {
    val hb = HeartBeatCheck.thresholding[Provider](new HeartBeatCheck.TimingBeat(tm, 2.seconds))
    val rl = RateLimiter.inFlight[Provider, String](perProviderLimit)
    
    new LoadBalancer(new RoundRobinBalancing, heartBeatCheck = Some(hb), requestRateLimit = Some(rl))
  }

  test("Happy-path scenario should succeed") {
    val tm = new Timer("heartbeeat-timer")
    val lb = provide(tm)

    val provider1 = blockingProvider()
    val provider2 = blockingProvider()

    lb.register(provider1)
    lb.register(provider2)

    Future.traverse(1 to 100)(_ => lb.get().attempt)
      .map { results =>
        val successful = results.collect { case Success(id) => id }
        val groupped   = successful.groupBy(identity).view.mapValues(_.size).toMap

        assertEquals(successful.size, 100)
        assertEquals(groupped, Map(provider1.id -> 50, provider2.id -> 50))
      }
      .onComplete(_ => tm.cancel())
  }

  test("Exceeding requests should be failed") {
    val tm = new Timer("heartbeeat-timer")
    val lb = provide(tm, perProviderLimit = 25)

    val provider1 = blockingProvider()
    val provider2 = blockingProvider()

    lb.register(provider1)
    lb.register(provider2)

    Future.traverse(1 to 100)(_ => lb.get().attempt)
      .map { results =>
        val successful = results.collect { case Success(id) => id }
        val groupped   = successful.groupBy(identity).view.mapValues(_.size).toMap

        assertEquals(successful.size, 50)
        assertEquals(groupped, Map(provider1.id -> 25, provider2.id -> 25))
      }
      .onComplete(_ => tm.cancel())
  }
}
