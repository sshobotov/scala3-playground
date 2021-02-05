package dev.sshobotov

import java.util.{Timer, TimerTask}
import java.util.concurrent.locks.ReentrantLock

import scala.concurrent.{blocking, ExecutionContext, Future}
import scala.concurrent.duration.FiniteDuration
import HeartBeatCheck._

/**
 * Does liveness/availablity check and notifies if check status has changed
 */
abstract class HeartBeatCheck[T] {

  /**
   * @param checklist non-blocking function that provides fresh list of keys and respective checks
   * @param notify    non-blocking function should be called when decided that check status for key is decided
   * @return          function allows to stop started heartbeeat check
   */
  def start(checklist: Publisher[Checklist[T]], notify: ResultSubscriber[T]): Cancellation
}

object HeartBeatCheck {

  type Cancellation = () => Unit

  type Publisher[T] = () => T
  type Checklist[T] = Map[T, () => Future[Boolean]]

  type ResultSubscriber[T] = (T, Boolean) => Unit

  /**
   * @param heartbeat         logic decides when actually to run checks
   * @param comebackThreshold number of sequential successful heartbeat checks to notify entry came back, default=2
   * @param asyncContext      non-blocling ExecutionContext to forward Future results, default=global
   */
  def thresholding[T](
    heartbeat: HeartBeatCheck.HeartBeat,
    comebackThreshold: Byte = 2,
    asyncContext: ExecutionContext = ExecutionContext.global
  ): HeartBeatCheck[T] = {
    assert(comebackThreshold > 0, "comebackThreshold should be > 0")

    new HeartBeatCheck[T] {
      type SuccessfulResponsesCnt = Byte

      override def start(
        checklist: Publisher[Checklist[T]],
        notify: ResultSubscriber[T]
      ): Cancellation = {
        given ExecutionContext = asyncContext

        var dubiousItems = Map.empty[T, SuccessfulResponsesCnt]
        val lock         = new ReentrantLock

        def withLock[T](body: => T): T = {
          lock.lock()
          try { body } finally { lock.unlock() }
        }

        def resolve(entry: T, result: Boolean): Option[Boolean] =
          if (!result) {
            withLock {
              dubiousItems = dubiousItems.updated(entry, 0)
            }
            Some(result)
          } else {
            withLock {
              dubiousItems.get(entry) match {
                case Some(cnt) if cnt + 1 == comebackThreshold => 
                  dubiousItems = dubiousItems.removed(entry)
                  Some(true)
                case Some(cnt) =>
                  dubiousItems = dubiousItems.updated(entry, (cnt + 1).toByte)
                  None
                case _ =>
                  None
              }
            }
          }

        heartbeat.start(() => {
          checklist().map { case (e, check) =>
            check()
              .recover { case _ => false }
              .foreach {
                // Reolve and notify ASAP to reduce errors number for incoming requests
                resolve(e, _).foreach {
                  notify(e, _)
                }
              }
          }
        })
      }
    }
  }
  
  /**
   * Abstraction over clock time changes to execute actions at expected time
   */
  abstract class HeartBeat {
  
    /**
     * @param onBeat non-blocking callback function would be triggered when time will come
     */
    def start(onBeat: () => Unit): Cancellation
  }

  class TimingBeat(timer: Timer, interval: FiniteDuration) extends HeartBeat {

    override def start(onBeat: () => Unit): Cancellation = {
      val task = new TimerTask {
        override def run(): Unit = try { onBeat() } catch { case err => () }
      }
      timer.schedule(task, interval.toMillis)

      () => task.cancel()
    }
  }
}
