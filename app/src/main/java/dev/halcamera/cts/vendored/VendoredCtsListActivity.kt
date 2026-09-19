package dev.halcamera.cts.vendored

import android.content.Intent
import android.os.Bundle
import dev.halcamera.cts.suite.CtsChecklistActivity
import dev.halcamera.cts.suite.SuiteItem
import dev.halcamera.ctsvendor.VendoredCatalog
import dev.halcamera.ctsvendor.VendoredCts

/**
 * The vendored CTS list: a checklist of every `@Test` method of every class in [VendoredCatalog], one group per
 * class. The ticked methods run one after another through the suite run screen; the › of a row opens
 * [VendoredCaseActivity] for that method alone. Nothing here opens a camera.
 */
class VendoredCtsListActivity : CtsChecklistActivity() {
    override val screenTitle = "CTS 원문 케이스"
    override val intro = "목록 순서로 실행 · 전체 카메라 대상\n중단 시 현재 테스트 FAIL · › 개별 검사"
    override val disclaimer = VendoredReportPresenter.DISCLAIMER
    override val prefsName = "cts_vendored"

    override fun onCreate(savedInstanceState: Bundle?) {
        VendoredCts.install(this)
        super.onCreate(savedInstanceState)
    }

    override fun groups(): List<Group> = VendoredCatalog.tests().groupBy { it.className }.map { (className, tests) ->
        Group(className.substringAfterLast('.'), className, tests.map { SuiteItem.Vendored(it) })
    }

    override fun singleIntent(item: SuiteItem): Intent =
        Intent(this, VendoredCaseActivity::class.java).putExtra(VendoredCaseActivity.EXTRA_TEST_ID, (item as SuiteItem.Vendored).test.id)
}
