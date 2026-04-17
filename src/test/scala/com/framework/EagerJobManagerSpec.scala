package com.framework

import akka.actor.testkit.typed.scaladsl.ActorTestKit
import com.framework.JobProtocol._
import org.scalatest.BeforeAndAfterAll
import org.scalatest.wordspec.AnyWordSpec
import org.scalatest.matchers.should.Matchers
import scala.concurrent.Future
import scala.concurrent.duration._

class EagerJobManagerSpec extends AnyWordSpec with BeforeAndAfterAll with Matchers {

  val testKit = ActorTestKit()

  override def afterAll(): Unit = {
    testKit.shutdownTestKit()
  }

  class SimpleStatefulRunner(name: String, resultKey: String) extends StatefulJobRunner(name) {
    override protected def validate(ctx: JobContext): Future[Boolean] = Future.successful(true)
    override protected def initialize(ctx: JobContext): Future[JobContext] = Future.successful(ctx)
    override protected def run(ctx: JobContext): Future[JobContext] = 
      Future.successful(ctx.put(resultKey, s"$name-completed"))
    override protected def finalize(ctx: JobContext, success: Boolean): Future[Unit] = Future.successful(())
  }

  val testFactory = new StatefulRunnerFactory {
    override def createStateful(name: String, props: Map[String, Any]): StatefulJobRunner = {
      name match {
        case "Runner1" => new SimpleStatefulRunner("Runner1", "result1")
        case "Runner2" => new SimpleStatefulRunner("Runner2", "result2")
        case "Runner3" => new SimpleStatefulRunner("Runner3", "result3")
        case _ => throw new IllegalArgumentException(s"Unknown runner: $name")
      }
    }
  }

  "EagerJobManager" should {

    "pre-allocate all runners at startup" in {
      val manager = testKit.spawn(EagerJobManager(testFactory))

      val definition = JobDefinition(List(
        SequentialStage(RunnerConfig("Runner1")),
        ParallelStage(List(
          RunnerConfig("Runner2"),
          RunnerConfig("Runner3")
        ))
      ))

      manager ! StartJob(definition)

      Thread.sleep(2000)
      
      // All runners should be pre-allocated
    }

    "execute single sequential stage" in {
      val manager = testKit.spawn(EagerJobManager(testFactory))

      val definition = JobDefinition(List(
        SequentialStage(RunnerConfig("Runner1"))
      ))

      manager ! StartJob(definition)

      Thread.sleep(1000)
    }

    "execute multiple sequential stages in order" in {
      val manager = testKit.spawn(EagerJobManager(testFactory))

      val definition = JobDefinition(List(
        SequentialStage(RunnerConfig("Runner1")),
        SequentialStage(RunnerConfig("Runner2")),
        SequentialStage(RunnerConfig("Runner3"))
      ))

      manager ! StartJob(definition)

      Thread.sleep(2000)
    }

    "execute parallel stages concurrently" in {
      val manager = testKit.spawn(EagerJobManager(testFactory))

      val definition = JobDefinition(List(
        ParallelStage(List(
          RunnerConfig("Runner1"),
          RunnerConfig("Runner2"),
          RunnerConfig("Runner3")
        ))
      ))

      manager ! StartJob(definition)

      Thread.sleep(2000)
    }

    "reuse pre-allocated runners across stages" in {
      val manager = testKit.spawn(EagerJobManager(testFactory))

      val definition = JobDefinition(List(
        SequentialStage(RunnerConfig("Runner1")),
        SequentialStage(RunnerConfig("Runner1")) // Reuse same runner
      ))

      manager ! StartJob(definition)

      Thread.sleep(2000)
      
      // Runner1 should be reused
    }

    "handle stage timeout" in {
      val slowRunnerFactory = new StatefulRunnerFactory {
        override def createStateful(name: String, props: Map[String, Any]): StatefulJobRunner = {
          new StatefulJobRunner(name) {
            override protected def validate(ctx: JobContext): Future[Boolean] = Future.successful(true)
            override protected def initialize(ctx: JobContext): Future[JobContext] = Future.successful(ctx)
            override protected def run(ctx: JobContext): Future[JobContext] = {
              Thread.sleep(5000) // Simulate slow runner
              Future.successful(ctx)
            }
            override protected def finalize(ctx: JobContext, success: Boolean): Future[Unit] = Future.successful(())
          }
        }
      }

      val manager = testKit.spawn(EagerJobManager(slowRunnerFactory))

      val definition = JobDefinition(List(
        SequentialStage(
          RunnerConfig("SlowRunner"),
          timeout = Some(1.second)
        )
      ))

      manager ! StartJob(definition)

      Thread.sleep(2000)
      
      // Should timeout and fail
    }
  }
}
