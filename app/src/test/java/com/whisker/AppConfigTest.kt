package com.whisker

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.whisker.data.ClientRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AppConfigTest {

  @Test
  fun testAppNameResource() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val appName = context.getString(R.string.app_name)
    assertEquals("Whisker", appName)
  }

  @Test
  fun testClientRequestAutoAccept() {
    val request = ClientRequest(
      uuid = "client-123",
      name = "Security Monitor",
      message = "Access request",
      status = "APPROVED",
      token = "pass-abc",
      autoAcceptCall = false
    )
    assertFalse(request.autoAcceptCall)

    val updated = request.copy(autoAcceptCall = true)
    assertTrue(updated.autoAcceptCall)
  }

  @Test
  fun testCallStateActiveTransition() {
    val activeState = com.whisker.data.CallState.Active(
      clientUuid = "client-123",
      clientName = "Security Monitor",
      isVideo = true,
      durationSeconds = 0,
      sdp = "test-sdp"
    )
    assertEquals("client-123", activeState.clientUuid)
    assertEquals("Security Monitor", activeState.clientName)
    assertTrue(activeState.isVideo)
    assertEquals(0, activeState.durationSeconds)
    assertEquals("test-sdp", activeState.sdp)
  }
}
