package com.rcs.healthcheck

import zio._
import sttp.client3._
import sttp.client3.circe._
import io.circe.generic.auto._
import cats.data.ValidatedNel
import cats.implicits._
import zio.DurationSyntax._

// Note: For EitherT usage with ZIO, use EitherT(zioEffect.either) to capture failures as Left,
// not EitherT.right(zioEffect) which leaks effect failures outside EitherT.
// Example:
// val z1: Task[Int] = ZIO.succeed(10)
// val z2: Task[String] = ZIO.fail(new RuntimeException("Boom!"))
// val z3: Task[Boolean] = ZIO.succeed(true)
// val result: EitherT[Task, Throwable, Boolean] = for {
//   num  <- EitherT.right(z1)
//   str  <- EitherT(z2.either)          // Task failure -> Left(Throwable)
//   bool <- EitherT.right(z3)
// } yield bool
// val finalEffect: Task[Boolean] = result.value.absolve

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
        private val timeoutDuration = zio.Duration.fromNanos(config.healthCheck.timeout.toNanos)

        override def runCheck(check: HealthCheck): ZIO[Any, Nothing, CheckResult] = check match {
          case DatabaseCheck =>
            // Simulate DB check - in real app, check actual DB connection
            ZIO.succeed(().validNel[HealthError]) // Represents success
          case InternetCheck =>
            // Check internet connectivity by pinging configured URL
            val request = basicRequest.get(uri"$connectivityUrl").response(asString)
            backend.send(request).foldZIO(
              _ => ZIO.succeed(ConnectivityError("Internet is down").invalidNel[Unit]),
              resp =>
                if (resp.code.isSuccess) ZIO.succeed(().validNel[HealthError])
                else ZIO.succeed(ConnectivityError(s"Connectivity check returned ${resp.code}").invalidNel[Unit])
            )
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
            check.race(ZIO.succeed(EndpointError(s"Endpoint $endpoint timed out").invalidNel[Unit]).delay(timeoutDuration))
        }

        override def checkAllHealths(checks: List[HealthCheck]): ZIO[Any, Nothing, CheckResult] = {
          ZIO.foreachPar(checks)(runCheck)
            .map(_.combineAll)
        }
      }
    }

  def runCheck(check: HealthCheck): ZIO[HealthCheckService, Nothing, CheckResult] =
    ZIO.serviceWithZIO(_.runCheck(check))

  def checkAllHealths(checks: List[HealthCheck]): ZIO[HealthCheckService, Nothing, CheckResult] =
    ZIO.serviceWithZIO(_.checkAllHealths(checks))
}