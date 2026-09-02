package sagablind.goblin

import sagablind.loader.SagaStepProvider

// ── MeasurementsService ───────────────────────────────────────────────────────
// Step 1 — measures the goblin.
// Inputs:  goblinId (String)
// Outputs: head (Int), armLength (Int), footSize (Int)

class MeasurementsService extends SagaStepProvider:
  def stepId = "measurements"

  def execute(args: Map[String, ujson.Value]): Either[Throwable, Map[String, ujson.Value]] =
    val goblinId = args("goblinId").str
    println(s"  [measurements] measuring goblin $goblinId...")
    Thread.sleep(300)
    Right(Map(
      "head"      -> ujson.Num(58),
      "armLength" -> ujson.Num(42),
      "footSize"  -> ujson.Num(38),
    ))

  def compensate(args: Map[String, ujson.Value]): Either[Throwable, Unit] =
    println(s"  [measurements] discarding measurements")
    Right(())
