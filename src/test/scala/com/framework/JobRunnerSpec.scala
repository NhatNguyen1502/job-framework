package com.framework

import akka.actor.testkit.typed.scaladsl.{ActorTestKit, TestProbe}
import com.framework.JobProtocol._
import com.framework.RunnerProtocol._
import org.scalatest.BeforeAndAfterAll
import org.scalatest.wordspec.AnyWordSpec
import org.scalatest.matchers.should.Matchers
import scala.concurrent.Future
import scala.concurrent.duration._

class JobRunnerSpec extends AnyWordSpec with BeforeAndAfterAll with Matchers {

  val testKit = ActorTestKit()

  override def afterAll(): Unit = {
    testKit.shutdownTestKit()
  }

  class TestRunner(
      name: String,
      validateResult: Boolean = true,
      shouldFailInitialize: Boolean = false,
      shouldFailRun: Boolean = false
  ) extends JobRunner(name) {

    var validateCalled = false
    var initializeCalled = false
    var runCalled = false
    var finalizeCalled = false

    override protected def validate(ctx: JobContext): Future[Boolean] = Future.successful {
      validateCalled = true
      validateResult
    }

    override protected def initialize(ctx: JobContext): Future[JobContext] = {
      initializeCalled = true
      if (shouldFailInitialize) {
        Future.failed(new RuntimeException("Initialize failed"))
      } else {
        Future.successful(ctx.put("initialized", true))
      }
    }

    override protected def run(ctx: JobContext): Future[JobContext] = {
      runCalled = true
      if (shouldFailRun) {
        Future.failed(new RuntimeException("Run failed"))
      } else {
        Future.successful(ctx.put("result", "success"))
      }
    }

    override protected def finalize(ctx: JobContext, success: Boolean): Future[Unit] = Future.successful {
      finalizeCalled = true
    }
  }

  "JobRunner" should {

    "execute full lifecycle successfully" in {
      val replyProbe = testKit.createTestProbe[JobRunnerResponse]()
      val runner = new TestRunner("test-runner")
      val runnerActor = testKit.spawn(runner())

      val initialCtx = JobContext().put("input", "data")
      runnerActor ! StartRunner(initialCtx, replyProbe.ref)

      val response = replyProbe.receiveMessage(3.seconds)
      
      response.runnerName shouldBe "test-runner"
      response.result shouldBe a[JobSucceeded]
      
      val succeeded = response.result.asInstanceOf[JobSucceeded]
      succeeded.updatedContext.get[String]("input") shouldBe Some("data")
      succeeded.updatedContext.get[Boolean]("initialized") shouldBe Some(true)
      succeeded.updatedContext.get[String]("result") shouldBe Some("success")

      runner.validateCalled shouldBe true
      runner.initializeCalled shouldBe true
      runner.runCalled shouldBe true
      runner.finalizeCalled shouldBe true
    }

    "fail if validation returns false" in {
      val replyProbe = testKit.createTestProbe[JobRunnerResponse]()
      val runner = new TestRunner("test-runner", validateResult = false)
      val runnerActor = testKit.spawn(runner())

      runnerActor ! StartRunner(JobContext(), replyProbe.ref)

      val response = replyProbe.receiveMessage(3.seconds)
      
      response.result shouldBe a[JobFailed]
      runner.validateCalled shouldBe true
      runner.initializeCalled shouldBe false
      runner.runCalled shouldBe false
    }

    "fail if initialize throws exception" in {
      val replyProbe = testKit.createTestProbe[JobRunnerResponse]()
      val runner = new TestRunner("test-runner", shouldFailInitialize = true)
      val runnerActor = testKit.spawn(runner())

      runnerActor ! StartRunner(JobContext(), replyProbe.ref)

      val response = replyProbe.receiveMessage(3.seconds)
      
      response.result shouldBe a[JobFailed]
      runner.validateCalled shouldBe true
      runner.initializeCalled shouldBe true
      runner.runCalled shouldBe false
    }

    "fail if run throws exception" in {
      val replyProbe = testKit.createTestProbe[JobRunnerResponse]()
      val runner = new TestRunner("test-runner", shouldFailRun = true)
      val runnerActor = testKit.spawn(runner())

      runnerActor ! StartRunner(JobContext(), replyProbe.ref)

      val response = replyProbe.receiveMessage(3.seconds)
      
      response.result shouldBe a[JobFailed]
      runner.validateCalled shouldBe true
      runner.initializeCalled shouldBe true
      runner.runCalled shouldBe true
    }

    "always call finalize regardless of success or failure" in {
      val replyProbe = testKit.createTestProbe[JobRunnerResponse]()
      val runner = new TestRunner("test-runner", shouldFailRun = true)
      val runnerActor = testKit.spawn(runner())

      runnerActor ! StartRunner(JobContext(), replyProbe.ref)

      replyProbe.receiveMessage(3.seconds)
      
      runner.finalizeCalled shouldBe true
    }

    "handle cancellation" in {
      val replyProbe = testKit.createTestProbe[JobRunnerResponse]()
      val runner = new TestRunner("test-runner")
      val runnerActor = testKit.spawn(runner())

      runnerActor ! StartRunner(JobContext(), replyProbe.ref)
      runnerActor ! CancelRunner

      val response = replyProbe.receiveMessage(3.seconds)
      
      response.result shouldBe a[JobFailed]
      runner.finalizeCalled shouldBe true
    }
  }
}
