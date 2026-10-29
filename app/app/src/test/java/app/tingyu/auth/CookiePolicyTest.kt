package app.tingyu.auth

import app.tingyu.data.auth.CookiePolicy
import org.junit.Assert.*
import org.junit.Test

class CookiePolicyTest {
    @Test fun retainsOnlyNecessaryFirstPartySessionCookies() {
        assertEquals("SESSDATA=encoded%2Fsession; DedeUserID=42", CookiePolicy.normalize("tracking=yes; SESSDATA=encoded%2Fsession; DedeUserID=42; Path=/; Secure"))
    }
    @Test fun rejectsHeaderInjection() {
        assertEquals("", CookiePolicy.normalize("SESSDATA=x\r\nX-Header: attack"))
        assertFalse(CookiePolicy.hasSession("bili_jct=csrf"))
        assertFalse(CookiePolicy.hasSession("SESSDATA="))
    }
    @Test fun exactDomainAndHttpsAreRequiredForLoginNavigation() {
        assertTrue(CookiePolicy.isTrustedPage("https://passport.bilibili.com/login"))
        assertFalse(CookiePolicy.isTrustedPage("https://bilibili.com.attacker.test/login"))
        assertFalse(CookiePolicy.isTrustedPage("https://bilibili.com@attacker.test/login"))
        assertFalse(CookiePolicy.isTrustedPage("https://attacker@bilibili.com/login"))
        assertFalse(CookiePolicy.isTrustedPage("http://passport.bilibili.com/login"))
        assertFalse(CookiePolicy.isTrustedPage("file:///data/data/app.tingyu/session"))
        assertFalse(CookiePolicy.isTrustedPage("https://passport.bilibili.com:8443/login"))
    }
}
