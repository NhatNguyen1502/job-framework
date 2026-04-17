package com.framework

import org.scalatest.wordspec.AnyWordSpec
import org.scalatest.matchers.should.Matchers

class JobContextSpec extends AnyWordSpec with Matchers {

  "JobContext" should {

    "store and retrieve values" in {
      val ctx = JobContext()
        .put("key1", "value1")
        .put("key2", 42)
        .put("key3", List(1, 2, 3))

      ctx.get[String]("key1") shouldBe Some("value1")
      ctx.get[Int]("key2") shouldBe Some(42)
      ctx.get[List[Int]]("key3") shouldBe Some(List(1, 2, 3))
    }

    "return None for non-existent keys" in {
      val ctx = JobContext()
      ctx.get[String]("missing") shouldBe None
    }

    "override existing values" in {
      val ctx = JobContext()
        .put("key", "original")
        .put("key", "updated")

      ctx.get[String]("key") shouldBe Some("updated")
    }

    "merge contexts" in {
      val ctx1 = JobContext().put("a", 1).put("b", 2)
      val ctx2 = JobContext().put("c", 3).put("b", 99)

      val merged = ctx1.merge(ctx2)

      merged.get[Int]("a") shouldBe Some(1)
      merged.get[Int]("b") shouldBe Some(99) // ctx2 value wins
      merged.get[Int]("c") shouldBe Some(3)
    }

    "create immutable copies" in {
      val ctx1 = JobContext().put("key", "value1")
      val ctx2 = ctx1.put("key", "value2")

      // Original should be unchanged
      ctx1.get[String]("key") shouldBe Some("value1")
      ctx2.get[String]("key") shouldBe Some("value2")
    }

    "handle complex types" in {
      case class User(name: String, age: Int)
      val user = User("Alice", 30)

      val ctx = JobContext().put("user", user)
      ctx.get[User]("user") shouldBe Some(user)
    }

    "list all keys" in {
      val ctx = JobContext()
        .put("key1", 1)
        .put("key2", 2)
        .put("key3", 3)

      ctx.keys should contain allOf ("key1", "key2", "key3")
    }
  }
}
