package com.example

import com.framework._
import com.framework.JobProtocol._
import scala.concurrent.Future
import scala.concurrent.ExecutionContext.Implicits.global

// ============================================
// Simple One-shot Runners (for LazyJobManager)
// ============================================

class SimpleJobRunner(name: String, delay: Int = 100) extends JobRunner(name) {
  
  override protected def validate(ctx: JobContext): Future[Boolean] = Future {
    println(s"[$name] Validating...")
    Thread.sleep(delay)
    true
  }

  override protected def initialize(ctx: JobContext): Future[JobContext] = Future {
    println(s"[$name] Initializing...")
    Thread.sleep(delay)
    ctx.put(s"$name-init", "done")
  }

  override protected def run(ctx: JobContext): Future[JobContext] = Future {
    println(s"[$name] Running...")
    Thread.sleep(delay)
    ctx.put(s"$name-result", s"Value from $name")
  }

  override protected def finalize(ctx: JobContext, success: Boolean): Future[Unit] = Future {
    println(s"[$name] Finalizing (Success: $success)")
    Thread.sleep(delay)
  }
}

// ============================================
// Stateful Runners (for EagerJobManager)
// ============================================

class SimpleStatefulRunner(name: String, delay: Int = 100) extends StatefulJobRunner(name) {
  
  override protected def validate(ctx: JobContext): Future[Boolean] = Future {
    println(s"[$name] [Stateful] Validating...")
    Thread.sleep(delay)
    true
  }

  override protected def initialize(ctx: JobContext): Future[JobContext] = Future {
    println(s"[$name] [Stateful] Initializing...")
    Thread.sleep(delay)
    ctx.put(s"$name-init", "done")
  }

  override protected def run(ctx: JobContext): Future[JobContext] = Future {
    println(s"[$name] [Stateful] Running...")
    Thread.sleep(delay)
    ctx.put(s"$name-result", s"Value from $name")
  }

  override protected def finalize(ctx: JobContext, success: Boolean): Future[Unit] = Future {
    println(s"[$name] [Stateful] Finalizing (Success: $success)")
    Thread.sleep(delay)
  }
}

// ============================================
// Factories
// ============================================

object MyRunnerFactory extends RunnerFactory {
  override def create(name: String, props: Map[String, Any]): JobRunner = name match {
    case "ValidateInputs" => new SimpleJobRunner("ValidateInputs", 50)
    case "ProcessPart1"   => new SimpleJobRunner("ProcessPart1", 200)
    case "ProcessPart2"   => new SimpleJobRunner("ProcessPart2", 150)
    case "GenerateReport" => new SimpleJobRunner("GenerateReport", 100)
    case _                => new SimpleJobRunner(name)
  }
}

object MyStatefulRunnerFactory extends StatefulRunnerFactory {
  override def createStateful(name: String, props: Map[String, Any]): StatefulJobRunner = name match {
    case "ValidateInputs" => new SimpleStatefulRunner("ValidateInputs", 50)
    case "ProcessPart1"   => new SimpleStatefulRunner("ProcessPart1", 200)
    case "ProcessPart2"   => new SimpleStatefulRunner("ProcessPart2", 150)
    case "GenerateReport" => new SimpleStatefulRunner("GenerateReport", 100)
    case _                => new SimpleStatefulRunner(name)
  }
}
