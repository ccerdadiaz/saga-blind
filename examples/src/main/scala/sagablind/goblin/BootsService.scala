package sagablind.goblin

import sagablind.loader.SagaStepProvider

// ── BootsService ──────────────────────────────────────────────────────────────
// Step 2b (parallel) — makes boots based on foot size.
// Inputs:  footSize (Int)
// Outputs: bootId (String), bootSize (String)

class BootsService extends SagaStepProvider:
  def stepId = "boots"

  def execute(args: Map[String, ujson.Value]): Either[Throwable, Map[String, ujson.Value]] =
    val footSize = args("footSize").num.toInt
    println(s"  [boots] crafting boots for foot size $footSize...")
    Thread.sleep(400)
    Right(Map(
      "bootId"   -> ujson.Str(s"B-${scala.util.Random.nextInt(9000) + 1000}"),
      "bootSize" -> ujson.Str(s"EU-$footSize"),
    ))

  def compensate(args: Map[String, ujson.Value]): Either[Throwable, Unit] =
    val bootId = args.get("bootId").map(_.str).getOrElse("unknown")
    println(s"  [boots] returning boots $bootId to stock")
    Right(())
