
package dev.sshobotov

import scala.concurrent.{ExecutionContext, Future}

class RoundRobinBalancingTest extends util.NamedFunSuite {
  test("pick nothing if no candidates was provided") {
    val strategy = new RoundRobinBalancing[Int]

    assertEquals(strategy.pick(), None)

    strategy.feed(Set.empty)
    assertEquals(strategy.pick(), None)
  }

  test("pick candidate if it was provided") {
    val candidate = 1
    val strategy  = new RoundRobinBalancing[Int]

    strategy.feed(Set(candidate))
    assertEquals(strategy.pick(), Some(candidate))
  }

  test("pick same candidate if no others left") {
    val candidate = 1
    val strategy  = new RoundRobinBalancing[Int]

    strategy.feed(Set(candidate))
    strategy.pick()
    assertEquals(strategy.pick(), Some(candidate))
  }

  test("pick all candidates sequentially") {
    val candidates = Set(1, 2, 3)
    val strategy   = new RoundRobinBalancing[Int]

    strategy.feed(candidates)
    val results = candidates.flatMap(_ => strategy.pick())

    assertEquals(results, candidates)
  }

  test("keep picking candidates in the same sequence") {
    val candidates = Set(1, 2, 3)
    val strategy   = new RoundRobinBalancing[Int]

    strategy.feed(candidates)
    val replicated = List.fill(2)(candidates).flatten
    val results    = replicated.flatMap(_ => strategy.pick())

    assertEquals(results, replicated)
  }

  test("pick candidates in expected sequence if candidates dropped after update") {
    val candidates = Set(1, 2, 3)
    val strategy   = new RoundRobinBalancing[Int]

    strategy.feed(candidates)
    strategy.pick()

    strategy.feed(candidates - 2)
    val result = strategy.pick()

    assertEquals(result, Some(3))
  }

  test("pick candidates in expected sequence if candidates inserted after update") {
    val candidates = Set(1, 2)
    val strategy   = new RoundRobinBalancing[Int]

    strategy.feed(candidates)
    strategy.pick()
    strategy.pick()

    strategy.feed(candidates + 3)
    val result = strategy.pick()

    assertEquals(result, Some(3))
  }

  given ExecutionContext = ExecutionContext.global

  test("pick all candidates sequentially in the presence of concurrency") {
    val candidates = Set(1, 2, 3)
    val strategy   = new RoundRobinBalancing[Int]

    strategy.feed(candidates)
    val concurrent = candidates.map(_ => Future { strategy.pick() })

    Future.sequence(concurrent).map { results =>
      assertEquals(results.flatten.toSet, candidates)
    }
  }

  test("pick and feed candidates in expected manner in the presence of concurrency") {
    val candidates = Set(1, 2, 3)
    val strategy   = new RoundRobinBalancing[Int]

    def pickAsync = Future { strategy.pick().get }

    strategy.feed(candidates)
    val concurrent = List(
      pickAsync,
      pickAsync,
      pickAsync,
      Future { strategy.feed(candidates - 3) },
      pickAsync,
      pickAsync,
      pickAsync
    )

    Future.sequence(concurrent).map { results =>
      val removedItemsSeenTimes = results.count(_ == 3)
      assert(removedItemsSeenTimes < 3)
    }
  }
}