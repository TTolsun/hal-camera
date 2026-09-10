`app/src/main/java/dev/halcamera/diagnosis/DiagnosisRules.kt` (81 lines). Picks one rule id from the metric states, per `PRODUCT-v0.2.md` chapter 8.

Twelve rules in a fixed priority order, highest first: `hard_failure`, `below_spec`, `pipeline_stall`, `sensor_stall`, `callback_delay`, `cdd_reference_exceeded`, `slower_than_baseline`, `three_a_unstable`, `cadence_change`, `three_a_searching`, `insufficient_evidence`, `normal`. Every rule that matched is kept as a secondary, with the evidence that triggered it, so the report can show why the chosen rule beat the others.

The rule ids are the layer's stable vocabulary: `CONSUMER_TEXT` maps each to one plain Korean sentence for the home and check screens, while expert views render the same id with its evidence. `RunSummary` reads the id out of a stored run and looks up the same map, so the wording exists in exactly one place.

The constraint stated at the top of the file is that a rule never names the HAL as a cause. `callback_delay` says the delay sits late in the pipeline; it does not claim to know whose fault that is. `cadence_change` goes further and says outright that a lower frame rate in a dark room is not a problem.
