package sagablind.goblin

import sagablind.loader.SagaStepProvider

// ── SmithyService ─────────────────────────────────────────────────────────────
// Step 2a (parallel) — forges a weapon based on arm length.
// Inputs:  armLength (Int)
// Outputs: weaponId (String), weaponType (String)

class SmithyService extends SagaStepProvider:
  def stepId = "smithy"

  def execute(args: Map[String, ujson.Value]): Either[Throwable, Map[String, ujson.Value]] =
    val armLength = args("armLength").num.toInt
    val weaponType = if armLength > 40 then "longsword" else "shortsword"
    println(s"  [smithy] forging $weaponType for arm length $armLength cm...")
    Thread.sleep(500)
    Right(Map(
      "weaponId"   -> ujson.Str(s"W-${scala.util.Random.nextInt(9000) + 1000}"),
      "weaponType" -> ujson.Str(weaponType),
    ))

  def compensate(args: Map[String, ujson.Value]): Either[Throwable, Unit] =
    val weaponId = args.get("weaponId").map(_.str).getOrElse("unknown")
    println(s"  [smithy] melting down weapon $weaponId")
    Right(())
