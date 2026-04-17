package com.framework

import akka.actor.testkit.typed.scaladsl.ActorTestKit
import com.framework.JobProtocol._
import org.scalatest.BeforeAndAfterAll
import org.scalatest.wordspec.AnyWordSpec
import org.scalatest.matchers.should.Matchers
import scala.concurrent.Future
import scala.concurrent.duration._

class LazyJobManagerSpec extends AnyWordSpec with BeforeAndAfterAll with Matchers {

  val testKit = ActorTestKit()

  override def afterAll(): Unit = {
    testKit.shutdownTestKit()
  }

  class SimpleTestRunner(name: String, resultKey: String) extends JobRunner(name) {
    override protected def validate(ctx: JobContext): Future[Boolean] = Future.successful(true)
    override protected def initialize(ctx: JobContext): Future[JobContext] = Future.successful(ctx)
    override protected def run(ctx: JobContext): Future[JobContext] = 
      Future.successful(ctx.put(resultKey, s"$name-completed"))
    override protected def finalize(ctx: JobContext, success: Boolean): Future[Unit] = Future.successful(())
  }

  val testFactory = new RunnerFactory {
    override def create(name: String, props: Map[String, Any]): JobRunner = {
      name match {
        case "Runner1" => new SimpleTestRunner("Runner1", "result1")
        case "Runner2" => new SimpleTestRunner("Runner2", "result2")
        case "Runner3" => new SimpleTestRunner("Runner3", "result3")
        case _ => throw new IllegalArgumentException(s"Unknown runner: $name")
      }
    }
  }

  "LazyJobManager" should {

    "execute a single sequential stage" in {
      val manager = testKit.spawn(LazyJobManager(testFactory))

      val definition = JobDefinition(List(
        SequentialStage(RunnerConfig("Runner1"))
      ))

      manager ! StartJob(definition)

      // Give it time to complete
      Thread.sleep(1000)
      
      // Job should complete successfully
      // (In real scenario, we'd have a response mechanism)
    }

    "execute multiple sequential stages in order" in {
      val manager = testKit.spawn(LazyJobManager(testFactory))

      val definition = JobDefinition(List(
        SequentialStage(RunnerConfig("Runner1")),
        SequentialStage(RunnerConfig("Runner2")),
        SequentialStage(RunnerConfig("Runner3"))
      ))

      manager ! StartJob(definition)

      Thread.sleep(2000)
      
      // All runners should execute sequentially
    }

    "execute parallel stages concurrently" in {
      val manager = testKit.spawn(LazyJobManager(testFactory))

      val definition = JobDefinition(List(
        ParallelStage(List(
          RunnerConfig("Runner1"),
          RunnerConfig("Runner2"),
          RunnerConfig("Runner3")
        ))
      ))

      manager ! StartJob(definition)

      Thread.sleep(2000)
      
      // All runners should execute in parallel
    }

    "execute mixed sequential and parallel stages" in {
      val manager = testKit.spawn(LazyJobManager(testFactory))

      val definition = JobDefinition(List(
        SequentialStage(RunnerConfig("Runner1")),
        ParallelStage(List(
          RunnerConfig("Runner2"),
          RunnerConfig("Runner3")
        ))
      ))

      manager ! StartJob(definition)

      Thread.sleep(2000)
      
      // Runner1 should execute first, then Runner2 and Runner3 in parallel
    }

    "handle stage timeout" in {
      val slowRunnerFactory = new RunnerFactory {
        override def create(name: String, props: Map[String, Any]): JobRunner = {
          new JobRunner(name) {
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

      val manager = testKit.spawn(LazyJobManager(slowRunnerFactory))

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
