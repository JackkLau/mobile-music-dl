package app.tingyu.auth

import app.tingyu.data.auth.LoginResponse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class LoginResponseTest {
    @Test fun authenticatedResponseCreatesAccount() {
        val account = LoginResponse.parse("""{"code":0,"data":{"isLogin":true,"mid":42,"uname":"测试用户","face":"http://i0.hdslb.com/face.jpg"}}""")
        assertEquals(42L, account.mid)
        assertEquals("测试用户", account.name)
        assertEquals("https://i0.hdslb.com/face.jpg", account.avatarUrl)
    }

    @Test fun cookiePresenceDoesNotTurnAnonymousNavIntoAuthenticatedAccount() {
        assertThrows(IllegalStateException::class.java) {
            LoginResponse.parse("""{"code":-101,"data":{"isLogin":false}}""")
        }
        assertThrows(IllegalStateException::class.java) {
            LoginResponse.parse("""{"code":0,"data":{"isLogin":false,"mid":42}}""")
        }
    }

    @Test fun invalidOrMissingUserIdFailsClosed() {
        assertThrows(IllegalStateException::class.java) {
            LoginResponse.parse("""{"code":0,"data":{"isLogin":true,"mid":0}}""")
        }
        assertThrows(IllegalStateException::class.java) {
            LoginResponse.parse("""{"code":0,"data":{"isLogin":true}}""")
        }
    }
}
