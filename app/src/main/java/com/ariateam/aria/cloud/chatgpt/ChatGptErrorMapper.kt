package com.ariateam.aria.cloud.chatgpt

import com.ariateam.aria.cloud.GeminiErrorKind
import com.ariateam.aria.cloud.GeminiResult
import org.json.JSONObject

object ChatGptErrorMapper {

    data class StructuredError(val code: String?, val message: String?, val param: String?)

    fun mapHttpError(
        statusCode: Int,
        responseBody: String?,
        errorMessage: String?,
        accessToken: String = "",
    ): GeminiResult.Error {
        val parsed = extractStructuredError(responseBody)
        val code = parsed?.code?.lowercase()
        val rawDetail = parsed?.message ?: errorMessage ?: "HTTP $statusCode"
        val safeDetail = sanitize(rawDetail, accessToken)

        return when {
            statusCode == 401 || code == "subscription_sharing_invalid_user" ||
                code == "invalid_token" || code == "invalid_api_key" || code == "token_expired" -> {
                GeminiResult.Error(
                    kind = GeminiErrorKind.AUTH_FAILED,
                    detail = "Auth error ($statusCode, code=$code): $safeDetail",
                    userMessageKorean = "ChatGPT 계정 인증이 만료되었거나 올바르지 않아요. 다시 로그인해 주세요.",
                )
            }
            statusCode == 403 -> {
                when (code) {
                    "subscription_sharing_user_not_eligible", "subscription_sharing_v2_user_not_eligible" -> {
                        GeminiResult.Error(
                            kind = GeminiErrorKind.AUTH_FAILED,
                            detail = "Permission ineligible ($statusCode, code=$code): $safeDetail",
                            userMessageKorean = "ChatGPT 플랜 사용 권한이 없거나 지원되지 않는 계정이에요.",
                        )
                    }
                    "chatpass_v2_scope_not_authorized", "chatpass_v2_invalid_authorization_context" -> {
                        GeminiResult.Error(
                            kind = GeminiErrorKind.AUTH_FAILED,
                            detail = "Scope unauthorized ($statusCode, code=$code): $safeDetail",
                            userMessageKorean = "ChatGPT 플랜 사용 권한이 허용되지 않았어요. 설정에서 다시 확인해 주세요.",
                        )
                    }
                    "subscription_sharing_route_not_supported", "subscription_sharing_v2_route_not_supported" -> {
                        GeminiResult.Error(
                            kind = GeminiErrorKind.AUTH_FAILED,
                            detail = "Route not supported ($statusCode, code=$code): $safeDetail",
                            userMessageKorean = "지원되지 않는 요청 경로예요.",
                        )
                    }
                    else -> {
                        GeminiResult.Error(
                            kind = GeminiErrorKind.AUTH_FAILED,
                            detail = "Forbidden ($statusCode, code=$code): $safeDetail",
                            userMessageKorean = "ChatGPT 서비스 접근 권한이 없어요.",
                        )
                    }
                }
            }
            statusCode == 404 || code == "model_not_found" -> {
                GeminiResult.Error(
                    kind = GeminiErrorKind.MODEL_UNAVAILABLE,
                    detail = "Model unavailable ($statusCode, code=$code): $safeDetail",
                    userMessageKorean = "선택한 ChatGPT 모델을 사용할 수 없어요. 지원되는 모델을 확인해 주세요.",
                )
            }
            statusCode == 429 || code == "subscription_sharing_usage_limit_exceeded" -> {
                val korean = if (code == "subscription_sharing_usage_limit_exceeded") {
                    "ChatGPT 사용 한도를 초과했어요. ChatGPT 설정의 사용량을 확인해 주세요."
                } else {
                    "ChatGPT 사용 한도를 초과했어요. 잠시 후 다시 시도해 주세요."
                }
                GeminiResult.Error(
                    kind = GeminiErrorKind.RATE_LIMIT,
                    detail = "Rate limit ($statusCode, code=$code): $safeDetail",
                    userMessageKorean = korean,
                )
            }
            statusCode == 408 || statusCode == 504 -> {
                GeminiResult.Error(
                    kind = GeminiErrorKind.TIMEOUT,
                    detail = "Timeout ($statusCode): $safeDetail",
                    userMessageKorean = "ChatGPT 응답 시간이 초과되었어요. 잠시 후 다시 질문해 주세요.",
                )
            }
            statusCode == 503 -> {
                when (code) {
                    "subscription_sharing_usage_unavailable" -> {
                        GeminiResult.Error(
                            kind = GeminiErrorKind.SERVER_ERROR,
                            detail = "Usage unavailable (503): $safeDetail",
                            userMessageKorean = "사용량 확인 서비스가 일시적으로 원활하지 않아요. 잠시 후 다시 시도해 주세요.",
                        )
                    }
                    "subscription_sharing_user_unavailable", "subscription_sharing_v2_user_unavailable" -> {
                        GeminiResult.Error(
                            kind = GeminiErrorKind.SERVER_ERROR,
                            detail = "User unavailable (503): $safeDetail",
                            userMessageKorean = "사용자 정보를 일시적으로 확인할 수 없어요. 잠시 후 다시 시도해 주세요.",
                        )
                    }
                    else -> {
                        if (errorMessage?.contains("Network unavailable", ignoreCase = true) == true) {
                            GeminiResult.Error(
                                kind = GeminiErrorKind.NETWORK_UNAVAILABLE,
                                detail = "Network unavailable: $safeDetail",
                                userMessageKorean = "네트워크에 연결할 수 없어요. 인터넷 연결을 확인해 주세요.",
                            )
                        } else {
                            GeminiResult.Error(
                                kind = GeminiErrorKind.SERVER_ERROR,
                                detail = "Server error (503): $safeDetail",
                                userMessageKorean = "ChatGPT 서비스에 일시적인 문제가 발생했어요. 잠시 후 다시 시도해 주세요.",
                            )
                        }
                    }
                }
            }
            statusCode == 499 -> {
                GeminiResult.Error(
                    kind = GeminiErrorKind.CANCELLED,
                    detail = "Cancelled: $safeDetail",
                    userMessageKorean = "질문이 취소되었어요.",
                )
            }
            else -> {
                GeminiResult.Error(
                    kind = GeminiErrorKind.SERVER_ERROR,
                    detail = "HTTP $statusCode error: $safeDetail",
                    userMessageKorean = "ChatGPT 서비스에 일시적인 문제가 발생했어요. 잠시 후 다시 시도해 주세요.",
                )
            }
        }
    }

    fun mapStreamFailure(
        errorCode: String?,
        errorMessage: String?,
        param: String? = null,
        accessToken: String = "",
    ): GeminiResult.Error {
        val code = errorCode?.lowercase()
        val safeDetail = sanitize(errorMessage ?: errorCode ?: "Stream failed", accessToken)

        return when (code) {
            "subscription_sharing_usage_limit_exceeded" -> {
                GeminiResult.Error(
                    kind = GeminiErrorKind.RATE_LIMIT,
                    detail = "Stream failure ($code): $safeDetail",
                    userMessageKorean = "ChatGPT 사용 한도를 초과했어요. ChatGPT 설정의 사용량을 확인해 주세요.",
                )
            }
            "subscription_sharing_usage_unavailable" -> {
                GeminiResult.Error(
                    kind = GeminiErrorKind.SERVER_ERROR,
                    detail = "Stream failure ($code): $safeDetail",
                    userMessageKorean = "사용량 확인 서비스가 일시적으로 원활하지 않아요. 잠시 후 다시 시도해 주세요.",
                )
            }
            "subscription_sharing_invalid_user" -> {
                GeminiResult.Error(
                    kind = GeminiErrorKind.AUTH_FAILED,
                    detail = "Stream failure ($code): $safeDetail",
                    userMessageKorean = "ChatGPT 계정 인증이 만료되었거나 올바르지 않아요. 다시 로그인해 주세요.",
                )
            }
            "subscription_sharing_user_not_eligible" -> {
                GeminiResult.Error(
                    kind = GeminiErrorKind.AUTH_FAILED,
                    detail = "Stream failure ($code): $safeDetail",
                    userMessageKorean = "ChatGPT 플랜 사용 권한이 없거나 지원되지 않는 계정이에요.",
                )
            }
            "subscription_sharing_unsupported_capability" -> {
                GeminiResult.Error(
                    kind = GeminiErrorKind.SERVER_ERROR,
                    detail = "Unsupported capability ($code, param=$param): $safeDetail",
                    userMessageKorean = "지원되지 않는 요청 형식이나 설정이에요.",
                )
            }
            else -> {
                GeminiResult.Error(
                    kind = GeminiErrorKind.SERVER_ERROR,
                    detail = "Stream failure ($code): $safeDetail",
                    userMessageKorean = "답변 생성 중 문제가 발생했어요. 잠시 후 다시 시도해 주세요.",
                )
            }
        }
    }

    fun sanitize(text: String?, accessToken: String = ""): String {
        if (text.isNullOrBlank()) return ""
        var result = text
        if (accessToken.isNotBlank()) {
            result = result.replace(accessToken, "[REDACTED_TOKEN]")
        }
        result = result
            .replace(Regex("""Bearer\s+[A-Za-z0-9._~+/-]+=*""", RegexOption.IGNORE_CASE), "Bearer [REDACTED_TOKEN]")
            .replace(Regex("""(ey[A-Za-z0-9_-]{10,}\.[A-Za-z0-9_-]{10,}\.[A-Za-z0-9_-]{10,})"""), "[REDACTED_JWT]")
            .replace(Regex("""(sess-[A-Za-z0-9_-]{20,})"""), "[REDACTED_SESSION]")
            .replace(Regex("""AIzaSy[A-Za-z0-9_-]{20,}"""), "[REDACTED_KEY]")
        return result
    }

    fun extractStructuredError(json: String?): StructuredError? {
        if (json.isNullOrBlank()) return null
        return try {
            val obj = JSONObject(json)
            val errorObj = obj.optJSONObject("error")
            val code = errorObj?.optString("code", null)?.ifEmpty { null }
                ?: obj.optString("code", null)?.ifEmpty { null }
                ?: obj.optString("error", null)?.ifEmpty { null }
            val message = errorObj?.optString("message", null)?.ifEmpty { null }
                ?: obj.optString("message", null)?.ifEmpty { null }
                ?: obj.optString("detail", null)?.ifEmpty { null }
            val param = errorObj?.optString("param", null)?.ifEmpty { null }
                ?: obj.optString("param", null)?.ifEmpty { null }

            if (code != null || message != null || param != null) {
                StructuredError(code = code, message = message, param = param)
            } else {
                null
            }
        } catch (_: Throwable) {
            null
        }
    }
}
