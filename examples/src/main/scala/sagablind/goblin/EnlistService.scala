package sagablind.goblin

import sagablind.loader.SagaStepProvider

// ── EnlistService ─────────────────────────────────────────────────────────────
// Step 4 — registers the goblin in the army.
// Inputs:  goblinId (String), weaponType (String), bootSize (String), hatSerialNumber (String)
// Outputs: enlistmentId (String)

class EnlistService extends SagaStepProvider:
  def stepId = "enlist"

  def execute(args: Map[String, ujson.Value]): Either[Throwable, Map[String, ujson.Value]] =
    val goblinId   = args("goblinId").str
    val weaponType = args("weaponType").str
    val bootSize   = args("bootSize").str
    val hat        = args("hatSerialNumber").str
    println(s"  [enlist] enlisting goblin $goblinId")
    println(s"           weapon: $weaponType, boots: $bootSize, helmet: $hat")
    Thread.sleep(200)
    val enlistmentId = s"E-${scala.util.Random.nextInt(9000) + 1000}"
    println(s"  [enlist] goblin $goblinId enlisted! ID: $enlistmentId — ready for battle!")
    Right(Map("enlistmentId" -> ujson.Str(enlistmentId)))

  def compensate(args: Map[String, ujson.Value]): Either[Throwable, Unit] =
    val enlistmentId = args.get("enlistmentId").map(_.str).getOrElse("unknown")
    println(s"  [enlist] removing goblin from army registry — enlistment $enlistmentId cancelled")
    Right(())
