package sagablind.dsl

import sagablind.core.*

// ── SagaDslParser ───────────────────────────────────────────────────────────
// Parses the .saga DSL file into a SagaDefinition.
// Placeholder — implementation pending DSL design.
//
// Example DSL:
//   saga: goblin-campaign
//   jar: /libs/goblin-services.jar
//   steps:
//     mandatory: measurements
//     parallel:
//       - smithy
//       - boots
//     optional: portrait
//     bestEffort: notification

object SagaDslParser:
  def parse(content: String): Either[String, SagaDefinition] =
    Left("SagaDslParser not yet implemented")
