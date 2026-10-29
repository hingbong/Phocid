package org.sunsetware.phocid

import java.io.File
import org.apache.commons.io.FilenameUtils
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.sunsetware.phocid.data.listDependencies

@RunWith(RobolectricTestRunner::class)
class DependencyLicenseTest {
    @Test
    fun noMissingLicenseText() {
        listDependencies { File(FilenameUtils.concat("src/main/assets", it)).readText() }
    }
}
