package com.framework

/**
 * Factory interfaces for creating JobRunners
 */

/**
 * RunnerFactory - For LazyJobManager
 * Creates one-shot runners that execute once and stop
 */
trait RunnerFactory {
  def create(name: String, props: Map[String, Any]): JobRunner
}

/**
 * StatefulRunnerFactory - For EagerJobManager
 * Creates stateful runners that can be triggered multiple times
 */
trait StatefulRunnerFactory {
  def createStateful(name: String, props: Map[String, Any]): StatefulJobRunner
}
