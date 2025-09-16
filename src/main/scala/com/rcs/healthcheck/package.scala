package com.rcs

package object healthcheck {
  import cats.data.ValidatedNel

  sealed trait HealthError { def message: String }
  final case class ConnectivityError(message: String) extends HealthError
  final case class EndpointError(message: String) extends HealthError
  final case class DatabaseError(message: String) extends HealthError
  type CheckResult = ValidatedNel[HealthError, Unit]
}