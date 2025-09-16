package com.rcs.healthcheck.unit

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import zio._
import sttp.client3._
import cats.data.ValidatedNel
import cats.implicits._
import com.rcs.healthcheck._

class HealthCheckServiceSpec extends AnyFlatSpec with Matchers {

  "HealthCheckService" should "create service instance successfully" in {
    // Test that we can create the service structure
    val service = new HealthCheckService {
      override def runCheck(check: HealthCheck): ZIO[Any, Nothing, CheckResult] = check match {
        case DatabaseCheck => ZIO.succeed(().validNel[HealthError])
        case InternetCheck => ZIO.succeed(().validNel[HealthError])
        case EndpointCheck(_) => ZIO.succeed(().validNel[HealthError])
      }

      override def checkAllHealths(checks: List[HealthCheck]): ZIO[Any, Nothing, CheckResult] = {
        ZIO.foreachPar(checks)(runCheck).map(_.combineAll)
      }
    }

    service should not be null
  }

  it should "handle different health check types" in {
    val databaseCheck = DatabaseCheck
    val internetCheck = InternetCheck
    val endpointCheck = EndpointCheck("http://test.com")

    databaseCheck shouldBe DatabaseCheck
    internetCheck shouldBe InternetCheck
    endpointCheck shouldBe EndpointCheck("http://test.com")
  }

  it should "combine health check results" in {
    // Test the combineAll functionality with Cats
    import cats.implicits._

    val success1: CheckResult = ().validNel[HealthError]
    val success2: CheckResult = ().validNel[HealthError]
    val failure: CheckResult = ConnectivityError("Failed").invalidNel[Unit]

    val combinedSuccess = List(success1, success2).combineAll
    val combinedWithFailure = List(success1, failure).combineAll

    combinedSuccess.isValid shouldBe true
    combinedWithFailure.isInvalid shouldBe true
  }
}
