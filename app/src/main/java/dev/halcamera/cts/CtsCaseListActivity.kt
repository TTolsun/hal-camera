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
    override val intro = "실행할 케이스를 고르면 위에서부터 차례로 실행합니다. 각 케이스는 자기 카메라를 열고 닫으며, 실행 중에 중단할 수 있습니다. ›를 누르면 케이스 하나만 여는 화면으로 갑니다."
    override val disclaimer = CaseReportPresenter.DISCLAIMER
    override val prefsName = "cts_cases"

    override fun groups(): List<Group> = listOf(Group("케이스", "", CtsCatalog.cases.map { SuiteItem.Custom(it) }))

    override fun singleIntent(item: SuiteItem): Intent =
        Intent(this, CtsCaseActivity::class.java).putExtra(CtsCaseActivity.EXTRA_CASE_ID, (item as SuiteItem.Custom).spec.id)
}
