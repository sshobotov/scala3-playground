## Sandbox load balancer built with Scala 3

Just an attempt to build a request load balancer with minimal dependencies to practice low-level concurrency primitives and get predictable performance.

### Usage

This is a normal [sbt](https://www.scala-sbt.org/1.x/docs/Setup.html) project. You can compile code with `sbt compile`, run tests with `sbt test` (or use `sbtn`).

### Assumptions

- Reads (calls for `get()`) are vastly outnumber any mutations of the state
- Async communication (API of Provider) will be limited by some sane timeouts within underlying library (HTTP client, etc.)
- User of the code will allign intervals of health checks with time needed to execute all checks on particular hardware
- It's totally fine for components to fail-fast in runtime if wrong arguments was passed (sane values would cause no problems)
