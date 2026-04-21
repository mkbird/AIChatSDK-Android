package com.aichatsdk

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aichatsdk.audio.NativeBridge
import com.aichatsdk.models.ChatAttachment
import com.aichatsdk.models.ChatMessage
import com.aichatsdk.models.Role
import com.aichatsdk.persistence.DataStoreChatStore
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class DataStoreChatStoreInstrumentedTest {

    @Test fun `save then load roundtrip preserves messages`() = runTest {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        // Use a unique store name so tests don't interfere.
        val store = DataStoreChatStore(ctx, storeName = "test_${UUID.randomUUID()}")
        val msgs = listOf(
            ChatMessage(text = "u1", role = Role.USER),
            ChatMessage(text = "a1", role = Role.ASSISTANT),
            ChatMessage(
                text = "with attach",
                role = Role.USER,
                attachments = listOf(ChatAttachment("image", "AAAA", "image/jpeg")),
            ),
        )
        store.save(msgs)
        val loaded = store.load()
        assertThat(loaded).hasSize(3)
        assertThat(loaded[2].attachments).hasSize(1)
        assertThat(loaded[2].attachments[0].kind).isEqualTo("image")
    }

    @Test fun `save trims to limit`() = runTest {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        val store = DataStoreChatStore(ctx, storeName = "test_${UUID.randomUUID()}", limit = 5)
        val msgs = (1..20).map { ChatMessage(text = "m$it", role = Role.USER) }
        store.save(msgs)
        val loaded = store.load()
        assertThat(loaded).hasSize(5)
        assertThat(loaded.first().text).isEqualTo("m16")
        assertThat(loaded.last().text).isEqualTo("m20")
    }

    @Test fun `clear removes all messages`() = runTest {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        val store = DataStoreChatStore(ctx, storeName = "test_${UUID.randomUUID()}")
        store.save(listOf(ChatMessage(text = "x", role = Role.USER)))
        store.clear()
        assertThat(store.load()).isEmpty()
    }
}

@RunWith(AndroidJUnit4::class)
class NativeBridgeInstrumentedTest {

    @Test fun `library load attempt completes without crashing`() {
        // Either loaded (real device with .so present) or failed cleanly. We do not
        // require it to be loaded, only that querying it does not throw.
        val available = NativeBridge.isAvailable
        if (!available) {
            // Recording the failure is enough — tests on dev hosts without ndk-built .so
            // simply mark this branch.
            assertThat(NativeBridge.loadFailure).isNotNull()
        } else {
            // If loaded, capability flags must be queryable.
            NativeBridge.hasOpus
            NativeBridge.hasApm
        }
    }
}
