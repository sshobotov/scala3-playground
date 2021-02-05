package dev.sshobotov

import java.util.concurrent.atomic.AtomicReference

import scala.math.Ordering
import scala.math.Ordered._
import scala.util.Random

/**
 * Makes decision which one of candidates to pick if any provided
 */
abstract class BalancingStrategy[T] {

  /**
   * Updates candidates list to make a decision about
   */
  def feed(candidates: Set[T]): Unit

  /**
   * Picks one candidate out of list based on internal state/logic
   */
  def pick(): Option[T]
}

class RandomBalancing[T](rnd: Random = Random) extends BalancingStrategy[T] {
  private val ref = new AtomicReference[Vector[T]](Vector.empty)

  override def feed(candidates: Set[T]): Unit = 
    ref.set(candidates.toVector)

  override def pick(): Option[T] =
    ref.get match {
      case list if list.isEmpty => None
      case list                 => Some(list(rnd.nextInt(list.size)))
    }
}

class RoundRobinBalancing[T: Ordering] extends BalancingStrategy[T] {
  type IndexAndBuffer = (Option[Int], Vector[T])

  private val queue = new AtomicReference[IndexAndBuffer]((None, Vector.empty))

  override def feed(candidates: Set[T]): Unit = {
    // Keep list ordered so it's easier to guarantee right sequence for dynamic list
    val ordered = candidates.toVector.sorted
    
    queue.updateAndGet { 
      case (None, _)       => (None, ordered)
      case (Some(lastUsedIndex), previousList) =>
        val lastUsedItem        = previousList(lastUsedIndex)
        val indexBeforeNextItem = ordered.indexWhere(_ > lastUsedItem) - 1
        
        (Some(indexBeforeNextItem).filterNot(_ < 0), ordered)
    }
  }

  override def pick(): Option[T] = {
    val (index, list) = queue.updateAndGet {
      case origin @ (lastIndex, list) =>
        if (list.isEmpty) origin
        else {
          val updatedIndex = lastIndex.map(x => (x + 1) % list.size)
          (updatedIndex.orElse(Some(0)), list)
        }
    }
    index.map(list(_))
  }
}