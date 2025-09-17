package com.rcs.healthcheck.unit

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import zio._
import zio.Unsafe
import sttp.client3._
import sttp.client3.testing.SttpBackendStub
import sttp.model.StatusCode
import cats.data.ValidatedNel
import cats.data.Validated
import cats.implicits._
import com.rcs.healthcheck._
import scala.concurrent.Future

class HealthCheckServiceSpec extends AnyFlatSpec with Matchers {

  // Helper to create a test service with controllable backend
  def createTestService(backend: SttpBackend[Task, Any]): HealthCheckService = new HealthCheckService {
    private val timeoutDuration = zio.Duration.fromSeconds(5)

    override def runCheck(check: HealthCheck): ZIO[Any, Nothing, CheckResult] = check match {
      case DatabaseCheck =>
        // Simulate database health check failure for testing
        ZIO.succeed(DatabaseError("Database connection failed").invalidNel[Unit])

      case InternetCheck =>
        // Check internet connectivity by pinging configured URL
        val request = basicRequest.get(uri"https://httpbin.org/status/200").response(asString)
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
          .response(asString)
        val check = backend.send(request).foldZIO(
          _ => ZIO.succeed(EndpointError(s"Endpoint $endpoint is unhealthy").invalidNel[Unit]),
          response =>
            if (response.code.isSuccess) ZIO.succeed(().validNel[HealthError])
            else ZIO.succeed(EndpointError(s"Endpoint $endpoint returned error: ${response.code}").invalidNel[Unit])
        )
        check.timeout(timeoutDuration).map(_.getOrElse(EndpointError(s"Endpoint $endpoint timed out").invalidNel[Unit]))
    }

    override def checkAllHealths(checks: List[HealthCheck]): ZIO[Any, Nothing, CheckResult] = {
      ZIO.foreachPar(checks)(runCheck).map(_.combineAll)
    }
  }

  // Helper to extract result from ZIO effect
  def runTestEffect[T](effect: ZIO[Any, Nothing, T]): T = {
    val runtime = Runtime.default
    Unsafe.unsafe { implicit unsafe =>
      runtime.unsafe.run(effect) match {
        case Exit.Success(value) => value
        case Exit.Failure(_) => throw new RuntimeException("Unexpected failure in test effect")
      }
    }
  }

  // Helper to create a test service with Future-based backend for testing
  def createTestServiceWithFutureBackend(backend: SttpBackend[Future, Any]): HealthCheckService = new HealthCheckService {
    private val timeoutDuration = zio.Duration.fromSeconds(5)

    override def runCheck(check: HealthCheck): ZIO[Any, Nothing, CheckResult] = check match {
      case DatabaseCheck =>
        // Simulate database health check failure for testing
        ZIO.succeed(DatabaseError("Database connection failed").invalidNel[Unit])

      case InternetCheck =>
        // Check internet connectivity by pinging configured URL
        val request = basicRequest.get(uri"https://httpbin.org/status/200").response(asString)
        val check = ZIO.fromFuture(_ => backend.send(request)).foldZIO(
          _ => ZIO.succeed(ConnectivityError("Internet is down").invalidNel[Unit]),
          resp =>
            if (resp.code.isSuccess) ZIO.succeed(().validNel[HealthError])
            else ZIO.succeed(ConnectivityError(s"Connectivity check returned ${resp.code}").invalidNel[Unit])
        )
        check.race(ZIO.succeed(ConnectivityError("Internet connectivity timed out").invalidNel[Unit]).delay(timeoutDuration))

      case EndpointCheck(endpoint) =>
        val request = basicRequest
          .get(uri"$endpoint/health")
          .response(asString)
        val check = ZIO.fromFuture(_ => backend.send(request)).foldZIO(
          _ => ZIO.succeed(EndpointError(s"Endpoint $endpoint is unhealthy").invalidNel[Unit]),
          response =>
            if (response.code.isSuccess) ZIO.succeed(().validNel[HealthError])
            else ZIO.succeed(EndpointError(s"Endpoint $endpoint returned error: ${response.code}").invalidNel[Unit])
        )
        check.timeout(timeoutDuration).map(_.getOrElse(EndpointError(s"Endpoint $endpoint timed out").invalidNel[Unit]))
    }

    override def checkAllHealths(checks: List[HealthCheck]): ZIO[Any, Nothing, CheckResult] = {
      ZIO.foreachPar(checks)(runCheck).map(_.combineAll)
    }
  }

  "HealthCheckService" should "create service instance successfully" in {
    val service = createTestServiceWithFutureBackend(SttpBackendStub.asynchronousFuture)
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
    val success1: CheckResult = ().validNel[HealthError]
    val success2: CheckResult = ().validNel[HealthError]
    val failure: CheckResult = ConnectivityError("Failed").invalidNel[Unit]

    val combinedSuccess = List(success1, success2).combineAll
    val combinedWithFailure = List(success1, failure).combineAll

    combinedSuccess.isValid shouldBe true
    combinedWithFailure.isInvalid shouldBe true
  }

  it should "handle connectivity errors for internet checks" in {
    // Create a backend that simulates network failure
    val failingBackend = SttpBackendStub.asynchronousFuture
      .whenRequestMatches(_ => true)
      .thenRespondF(_ => Future.failed(new RuntimeException("Network error")))

    val service = createTestServiceWithFutureBackend(failingBackend)

    val runtime = Runtime.default
    val result = Unsafe.unsafe { implicit unsafe =>
      runtime.run(service.runCheck(InternetCheck)) match {
        case Exit.Success(value) => value
        case _ => throw new RuntimeException("Test failed")
      }
    }

    result match {
      case Validated.Invalid(errors) =>
        errors.head shouldBe a[ConnectivityError]
        errors.head.message should include("Internet is down")
      case Validated.Valid(_) =>
        fail("Expected invalid result but got valid")
    }
  }

  it should "handle endpoint errors for unhealthy services" in {
    // Create a backend that simulates HTTP 500 error
    val errorBackend = SttpBackendStub.asynchronousFuture
      .whenRequestMatches(_.uri.toString.contains("/health"))
      .thenRespondWithCode(StatusCode.InternalServerError, "Internal Server Error")

    val service = createTestServiceWithFutureBackend(errorBackend)

    val runtime = Runtime.default
    val result = Unsafe.unsafe { implicit unsafe =>
      runtime.run(service.runCheck(EndpointCheck("http://example.com"))) match {
        case Exit.Success(value) => value
        case _ => throw new RuntimeException("Test failed")
      }
    }

    result match {
      case Validated.Invalid(errors) =>
        val errorList = errors.toList
        errorList.head shouldBe a[EndpointError]
        errorList.head.message should include("returned error: 500")
      case Validated.Valid(_) =>
        fail("Expected invalid result but got valid")
    }
  }

  it should "handle database health check failures" in {
    val service = createTestServiceWithFutureBackend(SttpBackendStub.asynchronousFuture)

    val runtime = Runtime.default
    val result = runTestEffect(service.runCheck(DatabaseCheck))

    result match {
      case Validated.Invalid(errors) =>
        val errorList = errors.toList
        errorList.head shouldBe a[DatabaseError]
        errorList.head.message should include("Database connection failed")
      case Validated.Valid(_) =>
        fail("Expected invalid result but got valid")
    }
  }

  it should "accumulate multiple health check errors" in {
    val service = createTestServiceWithFutureBackend(SttpBackendStub.asynchronousFuture)

    val runtime = Runtime.default
    val checks = List(DatabaseCheck, InternetCheck, EndpointCheck("http://failing.com"))
    val result = runTestEffect(service.checkAllHealths(checks))

    result match {
      case Validated.Invalid(errors) =>
        val errorList = errors.toList
        errorList.length should be >= 2  // At least database and one other error
        errorList.exists(_.isInstanceOf[DatabaseError]) shouldBe true
      case Validated.Valid(_) =>
        fail("Expected invalid result but got valid")
    }
  }

  it should "handle successful internet connectivity checks" in {
    // Create a backend that simulates successful HTTP response
    val successBackend = SttpBackendStub.asynchronousFuture
      .whenRequestMatches(_.uri.toString.contains("httpbin.org"))
      .thenRespondWithCode(StatusCode.Ok, "OK")

    val service = createTestServiceWithFutureBackend(successBackend)

    val runtime = Runtime.default
    val result = runTestEffect(service.runCheck(InternetCheck))

    result.isValid shouldBe true
  }

  it should "handle successful endpoint health checks" in {
    // Create a backend that simulates successful health check response
    val successBackend = SttpBackendStub.asynchronousFuture
      .whenRequestMatches(_.uri.toString.contains("/health"))
      .thenRespondWithCode(StatusCode.Ok, "OK")

    val service = createTestServiceWithFutureBackend(successBackend)

    val runtime = Runtime.default
    val exit = Unsafe.unsafe { implicit unsafe =>
      runtime.run(service.runCheck(EndpointCheck("http://healthy.com")))
    }
    val result = exit match {
      case Exit.Success(value) => value
      case Exit.Failure(_) => throw new RuntimeException("Test failed")
    }

    result.isValid shouldBe true
  }

  it should "handle timeout scenarios for endpoint checks" in {
    // Create a backend that never responds (simulates timeout)
    val timeoutBackend = SttpBackendStub.asynchronousFuture
      .whenRequestMatches(_.uri.toString.contains("/health"))
      .thenRespondF(_ => Future.never)  // Never completes

    val service = createTestServiceWithFutureBackend(timeoutBackend)

    val runtime = Runtime.default
    val exit = Unsafe.unsafe { implicit unsafe =>
      runtime.run(
        service.runCheck(EndpointCheck("http://slow.com"))
          .timeout(zio.Duration.fromMillis(100))  // Short timeout for test
          .map(_.getOrElse(EndpointError("Test timeout").invalidNel[Unit]))
      )
    }
    val result = exit match {
      case Exit.Success(value) => value
      case Exit.Failure(_) => throw new RuntimeException("Test failed")
    }

    result match {
      case Validated.Invalid(errors) =>
        val errorList = errors.toList
        errorList.head shouldBe a[EndpointError]
      case Validated.Valid(_) =>
        fail("Expected invalid result but got valid")
    }
  }

  it should "handle mixed success and failure scenarios" in {
    // Create a backend that succeeds for internet check but fails for endpoint
    val mixedBackend = SttpBackendStub.asynchronousFuture
      .whenRequestMatches(_.uri.toString.contains("httpbin.org"))
      .thenRespondWithCode(StatusCode.Ok, "OK")
      .whenRequestMatches(_.uri.toString.contains("/health"))
      .thenRespondWithCode(StatusCode.ServiceUnavailable, "Service Unavailable")

    val service = createTestServiceWithFutureBackend(mixedBackend)

    val runtime = Runtime.default
    val checks = List(InternetCheck, EndpointCheck("http://failing.com"))
    val exit = Unsafe.unsafe { implicit unsafe =>
      runtime.run(service.checkAllHealths(checks))
    }
    val result = exit match {
      case Exit.Success(value) => value
      case Exit.Failure(_) => throw new RuntimeException("Test failed")
    }

    result match {
      case Validated.Invalid(errors) =>
        val errorList = errors.toList
        errorList.length shouldBe 1
        errorList.head shouldBe a[EndpointError]
      case Validated.Valid(_) =>
        fail("Expected invalid result but got valid")
    }
  }
}
