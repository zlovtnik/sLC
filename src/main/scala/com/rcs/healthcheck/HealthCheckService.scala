package com.rcs.healthcheck

import zio._
import sttp.client3._
import sttp.client3.circe._
import io.circe.generic.auto._
import scala.concurrent.duration._
import cats.data.ValidatedNel
import cats.implicits._
import zio.DurationSyntax._

// Define a sealed trait for our health checks
sealed trait HealthCheck
case object DatabaseCheck extends HealthCheck
case object InternetCheck extends HealthCheck
case class EndpointCheck(endpoint: String) extends HealthCheck

trait HealthCheckService {
  def runCheck(check: HealthCheck): ZIO[Any, Nothing, CheckResult]
  def checkAllHealths(checks: List[HealthCheck]): ZIO[Any, Nothing, CheckResult]
}

object HealthCheckService {
  def live: ZLayer[SttpBackend[Task, Any] with AppConfig, Nothing, HealthCheckService] =
    ZLayer.fromFunction { (backend: SttpBackend[Task, Any], config: AppConfig) =>
      new HealthCheckService {
        private val connectivityUrl = config.healthCheck.connectivityUrl.getOrElse("https://www.google.com")

        override def runCheck(check: HealthCheck): ZIO[Any, Nothing, CheckResult] = check match {
          case DatabaseCheck =>
            // Simulate DB check - in real app, check actual DB connection
            ZIO.succeed(().validNel[String]) // Represents success
          case InternetCheck =>
            // Check internet connectivity by pinging configured URL
            val request = basicRequest.get(uri"$connectivityUrl").response(asString)
            backend.send(request).foldZIO(
              _ => ZIO.succeed("Internet is down".invalidNel[Unit]),
              _ => ZIO.succeed(().validNel[String])
            )
          case EndpointCheck(endpoint) =>
            val request = basicRequest
              .get(uri"$endpoint/health")
              .response(asJson[Map[String, String]])
            val check = backend.send(request).foldZIO(
              _ => ZIO.succeed(s"Endpoint $endpoint is unhealthy".invalidNel[Unit]),
              response => response.body match {
                case Right(_) => ZIO.succeed(().validNel[String])
                case Left(_) => ZIO.succeed(s"Endpoint $endpoint returned error".invalidNel[Unit])
              }
            )
            // Race with timeout
            check.race(ZIO.succeed(s"Endpoint $endpoint timed out".invalidNel[Unit]).delay(zio.Duration.fromSeconds(10)))
        }

        override def checkAllHealths(checks: List[HealthCheck]): ZIO[Any, Nothing, CheckResult] = {
          ZIO.foreachPar(checks)(runCheck)
            .map(results => results.sequence.void) // Use Cats' sequence to combine ValidatedNel
        }
      }
    }

  def runCheck(check: HealthCheck): ZIO[HealthCheckService, Nothing, CheckResult] =
    ZIO.serviceWithZIO(_.runCheck(check))

  def checkAllHealths(checks: List[HealthCheck]): ZIO[HealthCheckService, Nothing, CheckResult] =
    ZIO.serviceWithZIO(_.checkAllHealths(checks))
}