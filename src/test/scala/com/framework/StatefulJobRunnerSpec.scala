package com.framework

import akka.actor.testkit.typed.scaladsl.ActorTestKit
import com.framework.JobProtocol._
import com.framework.RunnerProtocol._
import org.scalatest.BeforeAndAfterAll
import org.scalatest.wordspec.AnyWordSpec
import org.scalatest.matchers.should.Matchers
import scala.concurrent.Future

class StatefulJobRunnerSpec extends AnyWordSpec with BeforeAndAfterAll with Matchers {

  val testKit = ActorTestKit()

  override def afterAll(): Unit = {
    testKit.shutdownTestKit()
  }

  class TestStatefulRunner(
      name: String,
      validateResult: Boolean = true,
      shouldFailRun: Boolean = false
  ) extends StatefulJobRunner(name) {

    var executionCount = 0

    override protected def validate(ctx: JobContext): Future[Boolean] = Future.successful(validateResult)

    override protected def initialize(ctx: JobContext): Future[JobContext] = 
      Future.successful(ctx.put("initialized", true))

    override protected def run(ctx: JobContext): Future[JobContext] = {
      executionCount += 1
      if (shouldFailRun) {
        Future.failed(new RuntimeException("Run failed"))
      } else {
        Future.successful(ctx.put("execution", executionCount))
      }
    }

    override protected def finalize(ctx: JobContext, success: Boolean): Future[Unit] = Future.successful(())
  }

  "StatefulJobRunner" should {

    "execute successfully and return to idle state" in {
      val replyProbe = testKit.createTestProbe[JobRunnerResponse]()
      val runner = new TestStatefulRunner("stateful-runner")
      val runnerActor = testKit.spawn(runner())

      runnerActor ! StartRunner(JobContext(), replyProbe.ref)

      val response = replyProbe.receiveMessage()
      response.result shouldBe a[JobSucceeded]
      runner.executionCount shouldBe 1
    }

    "be reusable for multiple executions" in {
      val replyProbe = testKit.createTestProbe[JobRunnerResponse]()
      val runner = new TestStatefulRunner("stateful-runner")
      val runnerActor = testKit.spawn(runner())

      // First execution
      runnerActor ! StartRunner(JobContext(), replyProbe.ref)
      replyProbe.receiveMessage()
      runner.executionCount shouldBe 1

      // Second execution
      runnerActor ! StartRunner(JobContext(), replyProbe.ref)
      replyProbe.receiveMessage()
      runner.executionCount shouldBe 2

      // Third execution
      runnerActor ! StartRunner(JobContext(), replyProbe.ref)
      replyProbe.receiveMessage()
      runner.executionCount shouldBe 3
    }

    "handle failures and still be reusable" in {
      val replyProbe = testKit.createTestProbe[JobRunnerResponse]()
      val runner = new TestStatefulRunner("stateful-runner", shouldFailRun = true)
      val runnerActor = testKit.spawn(runner())

      // First execution fails
      runnerActor ! StartRunner(JobContext(), replyProbe.ref)
      val response1 = replyProbe.receiveMessage()
      response1.result shouldBe a[JobFailed]

      // Still can execute again
      runnerActor ! StartRunner(JobContext(), replyProbe.ref)
      val response2 = replyProbe.receiveMessage()
      response2.result shouldBe a[JobFailed]
    }

    "ignore cancel when idle" in {
      val replyProbe = testKit.createTestProbe[JobRunnerResponse]()
      val runner = new TestStatefulRunner("stateful-runner")
      val runnerActor = testKit.spawn(runner())

      // Cancel while idle should be ignored
      runnerActor ! CancelRunner

      // Should still be able to execute
      runnerActor ! StartRunner(JobContext(), replyProbe.ref)
      val response = replyProbe.receiveMessage()
      response.result shouldBe a[JobSucceeded]
    }
  }
}
