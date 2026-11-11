package com.otscup.otsmail.data

import android.content.Context
import androidx.credentials.CreatePublicKeyCredentialRequest
import androidx.credentials.CreatePublicKeyCredentialResponse
import androidx.credentials.CredentialManager
import androidx.credentials.GetCredentialRequest
import androidx.credentials.GetPublicKeyCredentialOption
import androidx.credentials.PublicKeyCredential
import androidx.credentials.exceptions.CreateCredentialCancellationException
import androidx.credentials.exceptions.CreateCredentialCustomException
import androidx.credentials.exceptions.CreateCredentialException
import androidx.credentials.exceptions.CreateCredentialProviderConfigurationException
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialCustomException
import androidx.credentials.exceptions.GetCredentialException
import androidx.credentials.exceptions.GetCredentialProviderConfigurationException
import org.json.JSONObject

/**
 * Android Credential Manager 通行密钥（Passkey）管理器
 * 遵循 W3C WebAuthn Level 3 JSON 规范与 Android Credential Manager API。
 */
object PasskeyManager {

    /**
     * 将后端返回的创建 options 规整为 WebAuthn / CredentialManager 要求的 JSON 格式
     */
    fun prepareCreationJson(options: JSONObject): String {
        val root = JSONObject(options.toString())
        val userObj = root.optJSONObject("user")
        if (userObj != null) {
            val rawId = userObj.optString("id")
            if (rawId.isNotEmpty()) {
                val encodedId = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(
                    rawId.toByteArray(Charsets.UTF_8),
                )
                userObj.put("id", encodedId)
            }
        }
        return root.toString()
    }

    /**
     * 在设备上注册通行密钥
     * @return WebAuthn 注册完成响应 JSON 对象（包含 id, rawId, type, response 等）
     */
    suspend fun registerPasskey(context: Context, options: JSONObject): JSONObject {
        val jsonStr = prepareCreationJson(options)
        val credentialManager = CredentialManager.create(context)
        val request = CreatePublicKeyCredentialRequest(requestJson = jsonStr)

        try {
            val response = credentialManager.createCredential(context, request) as? CreatePublicKeyCredentialResponse
                ?: throw IllegalStateException("创建凭据返回了非预期响应")
            return JSONObject(response.registrationResponseJson)
        } catch (e: CreateCredentialCancellationException) {
            throw ApiException(1001, "已取消通行密钥创建")
        } catch (e: CreateCredentialProviderConfigurationException) {
            throw ApiException(1002, "设备未配置通行密钥支持服务（如 Google Play 服务或安全密钥）")
        } catch (e: CreateCredentialException) {
            throw ApiException(1003, friendlyProviderMessage(e.message, "创建通行密钥失败"))
        }
    }

    /**
     * 使用已注册通行密钥进行断言认证
     * @return WebAuthn 断言响应 JSON 对象（包含 id, rawId, type, response 等）
     */
    suspend fun authenticatePasskey(context: Context, options: JSONObject): JSONObject {
        val credentialManager = CredentialManager.create(context)
        val option = GetPublicKeyCredentialOption(requestJson = options.toString())
        val request = GetCredentialRequest(listOf(option))

        try {
            val response = credentialManager.getCredential(context, request)
            val cred = response.credential as? PublicKeyCredential
                ?: throw IllegalStateException("不支持的凭据类型: ${response.credential.type}")
            return JSONObject(cred.authenticationResponseJson)
        } catch (e: GetCredentialCancellationException) {
            throw ApiException(1001, "已取消通行密钥验证")
        } catch (e: GetCredentialProviderConfigurationException) {
            throw ApiException(1002, "设备未配置通行密钥支持服务")
        } catch (e: GetCredentialException) {
            throw ApiException(1003, friendlyProviderMessage(e.message, "通行密钥验证失败"))
        }
    }

    /**
     * Google Play Services 的 RP ID 校验错误（50151/50152）原文是英文内部报错，
     * 直接甩给用户看不懂，统一翻译成可操作的提示。
     */
    private fun friendlyProviderMessage(raw: String?, fallback: String): String {
        val message = raw.orEmpty()
        val rpIdInvalid = message.contains("50152") || message.contains("50151") ||
            message.contains("RP ID cannot be validated", true) ||
            message.contains("rp Id cannot be validated", true)
        if (rpIdInvalid) {
            return "服务器未完成通行密钥域名关联（assetlinks.json 缺失或签名不匹配），请联系服务器管理员"
        }
        return message.ifEmpty { fallback }
    }
}
