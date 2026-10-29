package app.tingyu.data.auth

import app.tingyu.model.Account
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/** Authentication succeeds only on an explicit authenticated response, not the presence of cookies. */
object LoginResponse {
    fun parse(text: String): Account {
        val result = Json.parseToJsonElement(text).jsonObject
        check(result["code"]?.jsonPrimitive?.intOrNull == 0) { "登录尚未完成或已失效，请重新登录。" }
        val data = result["data"]?.jsonObject ?: error("登录验证信息不完整。")
        check(data["isLogin"]?.jsonPrimitive?.booleanOrNull == true) { "登录尚未完成或已失效，请重新登录。" }
        val mid = data["mid"]?.jsonPrimitive?.longOrNull ?: error("登录验证信息不完整。")
        check(mid > 0) { "登录验证信息不完整。" }
        val name = data["uname"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() } ?: "B 站用户"
        val avatar = data["face"]?.jsonPrimitive?.contentOrNull.orEmpty().replaceFirst("http://", "https://")
        return Account(mid, name, avatar)
    }
}
