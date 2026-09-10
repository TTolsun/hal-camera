`app/src/main/java/dev/halcamera/home/HomeActivity.kt`, the launcher activity (`android.intent.category.LAUNCHER`, `HomeTheme`).

Stateless by design: `onResume` calls `render()`, which clears the body `LinearLayout` and rebuilds every view from `RunSummary.load(this)`. There is no view model and no cached state, so a run finished in `CheckActivity` shows up simply by returning here.

It offers four destinations: run a new Auto Check (after requesting CAMERA through `ActivityResultContracts.RequestPermission`), open the newest stored run read-only, record an incident in consumer wording, and enter the expert instrument view. Copy on this screen is Korean and deliberately non-technical; the metric ids never appear.
