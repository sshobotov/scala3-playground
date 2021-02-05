package dev.sshobotov

import scala.concurrent.{ExecutionContext, Future}

class RandomBalancingTest extends util.NamedFunSuite {
  test("pick nothing if no candidates was provided") {
    val strategy = new RandomBalancing[Int]

    assertEquals(strategy.pick(), None)

    strategy.feed(Set.empty)
    assertEquals(strategy.pick(), None)
  }

  test("pick candidate if it was provided") {
    val candidate = 1
    val strategy  = new RandomBalancing[Int]

    strategy.feed(Set(candidate))
    assertEquals(strategy.pick(), Some(candidate))
  }

  test("pick same candidate if no others left") {
    val candidate = 1
    val strategy  = new RandomBalancing[Int]

    strategy.feed(Set(candidate))
    strategy.pick()

    assertEquals(strategy.pick(), Some(candidate))
  }

  test("pick candidate randomly") {
    val candidates = (1 to 100).toSet
    
    def firstPick = {
      val strategy = new RandomBalancing[Int]
      strategy.feed(candidates)
      strategy.pick().get
    }

    val a = firstPick
    val b = firstPick
    val c = firstPick

    assert(a != b || a != c)

    val strategy = new RandomBalancing[Int]
    strategy.feed(candidates)

    def sequentialPick = strategy.pick().get

    val d = sequentialPick
    val e = sequentialPick
    val f = sequentialPick

    assert(a != b || a != c)
  }

  given ExecutionContext = ExecutionContext.global

  test("pick candidate in expected way in the preesencee of concurrency") {
    val candidates = (1 to 100).toSet
    val strategy   = new RandomBalancing[Int]
    def pickAsync  = Future { strategy.pick().get }

    strategy.feed(candidates)

    val concurrent = Vector(pickAsync, pickAsync, pickAsync)
    Future.sequence(concurrent).map { reesults =>
      assert(reesults(0) != reesults(1) || reesults(0) != reesults(2))
    }
  }
}
