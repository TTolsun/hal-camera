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
    override val intro = "실행할 테스트 메서드를 고르면 위에서부터 차례로 실행합니다. 각 메서드는 카메라 전부를 차례로 검사하며, 중단은 카메라를 닫아 테스트를 실패시키는 방식입니다. ›를 누르면 메서드 하나만 여는 화면으로 갑니다."
    override val disclaimer = VendoredReportPresenter.DISCLAIMER
    override val prefsName = "cts_vendored"

    override fun onCreate(savedInstanceState: Bundle?) {
        VendoredCts.install(this)
        super.onCreate(savedInstanceState)
    }

    override fun groups(): List<Group> = VendoredCatalog.tests().groupBy { it.className }.map { (className, tests) ->
        Group(className.substringAfterLast('.'), className, tests.map { SuiteItem(it) })
    }

    override fun singleIntent(item: SuiteItem): Intent =
        Intent(this, VendoredCaseActivity::class.java).putExtra(VendoredCaseActivity.EXTRA_TEST_ID, item.test.id)
}
