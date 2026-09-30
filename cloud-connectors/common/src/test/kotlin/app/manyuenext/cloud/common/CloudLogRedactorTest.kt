package app.manyuenext.cloud.common

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CloudLogRedactorTest {
    @Test
    fun `all credential forms are redacted`() {
        val input = "Authorization: Bearer abc Token xyz Cookie: AVS=secret avs=foo jwt=bar api-key=key password=p secret=s"
        val output = CloudLogRedactor.redact(input)
        assertTrue(output.contains("<redacted>"))
        listOf("abc", "xyz", "secret", "foo", "bar", "key", "password=p").forEach { value ->
            assertFalse(output.contains(value), "credential leaked: $value")
        }
    }
}
