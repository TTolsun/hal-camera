package dev.halcamera.cts

import android.content.Intent
import dev.halcamera.cts.suite.CtsChecklistActivity
import dev.halcamera.cts.suite.SuiteItem

/**
 * The custom CTS case list: a checklist of every [CtsCatalog] entry. The ticked cases run one after another in
 * list order through the suite run screen; the › of a row opens [CtsCaseActivity] for that case alone. Nothing
 * here opens a camera; LIVE has already closed its session before this screen starts.
 */
class CtsCaseListActivity : CtsChecklistActivity() {
    override val screenTitle = "커스텀 케이스"
    override val intro = "선택한 케이스를 순서대로 실행합니다. › 개별 검사"
    override val disclaimer = CaseReportPresenter.DISCLAIMER
    override val prefsName = "cts_cases"

    override fun groups(): List<Group> = listOf(Group("케이스", "", CtsCatalog.cases.map { SuiteItem.Custom(it) }))

    override fun singleIntent(item: SuiteItem): Intent =
        Intent(this, CtsCaseActivity::class.java).putExtra(CtsCaseActivity.EXTRA_CASE_ID, (item as SuiteItem.Custom).spec.id)
}
