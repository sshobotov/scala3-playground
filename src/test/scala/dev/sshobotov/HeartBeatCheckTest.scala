package dev.sshobotov

import java.util.concurrent.atomic.{AtomicInteger, AtomicReference}

import scala.collection.mutable.ListBuffer
import scala.concurrent.Future

class HeartBeatCheckTest extends util.NamedFunSuite {
  class ManualHeartBeat extends HeartBeatCheck.HeartBeat {
    import HeartBeatCheck.Cancellation

    private val callbacks = new AtomicReference[Set[() => Unit]](Set.empty)

    override def start(onBeat: () => Unit): Cancellation = {
      callbacks.updateAndGet(_ + onBeat)
      () => { callbacks.updateAndGet(_ - onBeat); () }
    }

    def trigger: Unit = {
      callbacks.get.foreach(cb => cb())
      // To guarantee Futures were kicked-off (and so notifications order)
      Thread.sleep(10)
    }
  }

  def eventualy[T](block: => T, atMostTimes: Int = 10): T =
    try { block } catch {
      case e: AssertionError if atMostTimes > 1 =>
        Thread.sleep(10)
        eventualy(block, atMostTimes - 1)
    }

  test("notify straight away if check was unsuccessful") {
    val heartbeat = new ManualHeartBeat
    val check     = HeartBeatCheck.thresholding[String](heartbeat)

    val recorded  = new AtomicReference[Vector[(String, Boolean)]](Vector.empty)
    val failCheck = () => Future.successful(false)
    check.start(
      ()     => Map("test" -> failCheck),
      (k ,v) => { recorded.updateAndGet(_ :+ k -> v); () }
    )
    heartbeat.trigger

    eventualy {
      assertEquals(recorded.get, Vector(("test", false)))
    }
  }

  test("notify unsuccessful check if check was failed") {
    val heartbeat = new ManualHeartBeat
    val check     = HeartBeatCheck.thresholding[String](heartbeat)

    val recorded  = new AtomicReference[Vector[(String, Boolean)]](Vector.empty)
    val failCheck = () => Future.failed[Boolean](new RuntimeException("boom"))
    check.start(
      ()     => Map("test" -> failCheck),
      (k ,v) => { recorded.updateAndGet(_ :+ k -> v); () }
    )
    heartbeat.trigger

    eventualy {
      assertEquals(recorded.get, Vector(("test", false)))
    }
  }

  test("not notify if check was just successful") {
    val heartbeat = new ManualHeartBeat
    val check     = HeartBeatCheck.thresholding[String](heartbeat)

    val recorded = new AtomicReference[Vector[(String, Boolean)]](Vector.empty)
    val okCheck  = () => Future.successful(true)
    check.start(
      ()     => Map("test" -> okCheck),
      (k ,v) => { recorded.updateAndGet(_ :+ k -> v); () }
    )
    heartbeat.trigger

    eventualy {
      assertEquals(recorded.get, Vector.empty[(String, Boolean)])
    }
  }

  test("notify successful check if check was successful configured number of times after unsuccessful check") {
    val heartbeat = new ManualHeartBeat
    val check     = HeartBeatCheck.thresholding[String](heartbeat, comebackThreshold = 2)

    val recorded     = new AtomicReference[Vector[(String, Boolean)]](Vector.empty)
    val counter      = new AtomicInteger(0)
    val countedCheck = () => {
      val cnt = counter.incrementAndGet()
      if (cnt > 1) Future.successful(true)
      else Future.successful(false)
    }
    check.start(
      ()     => Map("test" -> countedCheck),
      (k ,v) => { recorded.updateAndGet(_ :+ k -> v); () }
    )
    heartbeat.trigger
    heartbeat.trigger
    heartbeat.trigger

    eventualy {
      assertEquals(recorded.get, Vector(("test", false), ("test", true)))
    }
  }

  test("not notify successful check if checks was successful but was interrupted by unsuccessful check") {
    val heartbeat = new ManualHeartBeat
    val check     = HeartBeatCheck.thresholding[String](heartbeat, comebackThreshold = 2)

    val recorded     = new AtomicReference[Vector[(String, Boolean)]](Vector.empty)
    var counter      = new AtomicInteger(0)
    val countedCheck = () => {
      val cnt = counter.incrementAndGet()
      if (cnt % 2 == 0) Future.successful(true)
      else Future.successful(false)
    }
    check.start(
      ()     => Map("test" -> countedCheck),
      (k ,v) => { recorded.updateAndGet(_ :+ k -> v); () }
    )
    heartbeat.trigger
    heartbeat.trigger
    heartbeat.trigger
    heartbeat.trigger

    eventualy {
      assertEquals(recorded.get, Vector(("test", false), ("test", false)))
    }
  }

  test("propagate cancellation to undeerlying HeartBeat") {
    val heartbeat = new ManualHeartBeat
    val check     = HeartBeatCheck.thresholding[String](heartbeat)

    val recorded = new AtomicReference[Vector[(String, Boolean)]](Vector.empty)
    val okCheck  = () => Future.successful(true)
    val cancel   = check.start(
      ()     => Map("test" -> okCheck),
      (k ,v) => { recorded.updateAndGet(_ :+ k -> v); () }
    )
    cancel()
    heartbeat.trigger

    eventualy {
      assertEquals(recorded.get, Vector.empty[(String, Boolean)])
    }
  }
}
