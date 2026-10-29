package app.tingyu.util

import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Test

class UserMessageTest {
    @Test fun preservesUsefulNestedExplanation() {
        assertEquals("本地音频已移除，请重新下载", userMessage(IOException("Load failed", IOException("本地音频已移除，请重新下载")), "失败"))
    }
    @Test fun neverDisplaysSignedUrlOrCredential() {
        assertEquals("请重试", userMessage(IOException("请求失败 https://example.test/audio?token=private"), "请重试"))
        assertEquals("请重试", userMessage(IOException("登录失败 Cookie: SESSDATA=private"), "请重试"))
    }
    @Test fun fallsBackForUncontrolledEnglishErrors() {
        assertEquals("网络连接失败", userMessage(IOException("Unable to resolve host"), "网络连接失败"))
    }
}
