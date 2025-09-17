package com.rcs.healthcheck

import zio._
import sttp.client3._
import sttp.client3.circe._
import io.circe.generic.auto._
import cats.data.ValidatedNel
import cats.implicits._

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
  def live: ZLayer[SttpBackend[Task, Any] with AppConfig, Throwable, HealthCheckService] =
    ZLayer.fromZIO {
      (for {
        backend <- ZIO.service[SttpBackend[Task, Any]]
        config <- ZIO.service[AppConfig]
        connectivityUrl <- ZIO.fromOption(config.healthCheck.connectivityUrl)
          .orElseFail(new RuntimeException("Missing healthCheck.connectivityUrl in application configuration. Please set this in your application.conf or environment variables."))
      } yield new HealthCheckService {
        private val timeoutDuration = zio.Duration.fromNanos(config.healthCheck.timeout.toNanos)

        override def runCheck(check: HealthCheck): ZIO[Any, Nothing, CheckResult] = check match {
          case DatabaseCheck =>
            // TODO: Implement real DB connectivity/health check
            // Perform a real DB ping/query or use existing DB client to verify connection
            // Translate failures into HealthError.DatabaseError
            // For now, this is a stub that always returns success
            ZIO.succeed(().validNel[HealthError]) // Represents success
          case InternetCheck =>
            // Check internet connectivity by pinging configured URL
            val request = basicRequest.get(uri"$connectivityUrl").response(asString)
            val check = backend.send(request).foldZIO(
              _ => ZIO.succeed(ConnectivityError("Internet is down").invalidNel[Unit]),
              resp =>
                if (resp.code.isSuccess) ZIO.succeed(().validNel[HealthError])
                else ZIO.succeed(ConnectivityError(s"Connectivity check returned ${resp.code}").invalidNel[Unit])
            )
            check.race(ZIO.succeed(ConnectivityError("Internet connectivity timed out").invalidNel[Unit]).delay(timeoutDuration))
          case EndpointCheck(endpoint) =>
            val request = basicRequest
              .get(uri"$endpoint/health")
              .response(asJson[Map[String, String]])
            val check = backend.send(request).foldZIO(
              _ => ZIO.succeed(EndpointError(s"Endpoint $endpoint is unhealthy").invalidNel[Unit]),
              response => response.body match {
                case Right(_) => ZIO.succeed(().validNel[HealthError])
                case Left(err) => ZIO.succeed(EndpointError(s"Endpoint $endpoint returned error: ${err}").invalidNel[Unit])
              }
            )
            // Race with timeout
            check.timeout(timeoutDuration).map(_.getOrElse(EndpointError(s"Endpoint $endpoint timed out").invalidNel[Unit]))
        }

        override def checkAllHealths(checks: List[HealthCheck]): ZIO[Any, Nothing, CheckResult] = {
          ZIO.foreachPar(checks)(runCheck)
            .map(_.combineAll)
        }
      }).tapError { error =>
        ZIO.succeed(
          println(s"Failed to provision HealthCheckService: ${error.getMessage}")
        )
      }
    }

  def runCheck(check: HealthCheck): ZIO[HealthCheckService, Nothing, CheckResult] =
    ZIO.serviceWithZIO(_.runCheck(check))

  def checkAllHealths(checks: List[HealthCheck]): ZIO[HealthCheckService, Nothing, CheckResult] =
    ZIO.serviceWithZIO(_.checkAllHealths(checks))
}