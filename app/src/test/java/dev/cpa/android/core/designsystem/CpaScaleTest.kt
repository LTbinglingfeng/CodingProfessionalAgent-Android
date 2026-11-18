package dev.cpa.android.core.designsystem

import org.junit.Assert.assertEquals
import org.junit.Test

class CpaScaleTest {
    @Test fun wideAndBaselinePhonesOnlyGetCompensation() {
        assertEquals(CpaScale.Compensation, CpaScale.ui(393), 1e-4f)
        assertEquals(CpaScale.Compensation, CpaScale.ui(412), 1e-4f)
        assertEquals(CpaScale.Compensation, CpaScale.ui(800), 1e-4f)
    }

    @Test fun narrowPhonesShrinkProportionallyButNeverBelowMin() {
        assertEquals(360 / 393f * CpaScale.Compensation, CpaScale.ui(360), 1e-4f)
        assertEquals(CpaScale.Min, CpaScale.ui(280), 1e-4f)
    }

    @Test fun enlargedSystemFontIsSoftenedButStillHonored() {
        assertEquals(1f, CpaScale.font(1f), 1e-4f)
        assertEquals(.85f, CpaScale.font(.85f), 1e-4f)
        assertEquals(1.3f, CpaScale.font(1.5f), 1e-4f)
        assertEquals(1.6f, CpaScale.font(2f), 1e-4f)
    }
}
