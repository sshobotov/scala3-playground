package dev.sshobotov

import scala.concurrent.Future
import scala.math.Ordering

abstract class Provider {
  def id: String
  def get(): Future[String] = Future.successful(id)
  def check(): Future[Boolean]
  override def hashCode() = id.hashCode()
}

object Provider {
  implicit val providerOrdering: Ordering[Provider] = Ordering.by(_.id)
}
