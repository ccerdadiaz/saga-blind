package sagablind.goblin

import sagablind.loader.SagaStepProvider

// ── HatService ────────────────────────────────────────────────────────────────
// Step 3 — gets a helmet based on head size.
// Compensation is expensive — always goes last.
// Inputs:  goblinId (String), head (Int)
// Outputs: hatSerialNumber (String)

class HatService extends SagaStepProvider:
  def stepId = "getHat"

  def execute(args: Map[String, ujson.Value]): Either[Throwable, Map[String, ujson.Value]] =
    val goblinId = args("goblinId").str
    val head     = args("head").num.toInt
    println(s"  [getHat] fitting helmet size $head for goblin $goblinId...")
    Thread.sleep(600)
    Right(Map(
      "hatSerialNumber" -> ujson.Str(s"H-${scala.util.Random.nextInt(9000) + 1000}"),
    ))

  def compensate(args: Map[String, ujson.Value]): Either[Throwable, Unit] =
    val serial = args.get("hatSerialNumber").map(_.str).getOrElse("unknown")
    println(s"  [getHat] returning helmet $serial — sending scaffolding team to retrieve it")
    Thread.sleep(800)  // compensation is expensive
    Right(())
