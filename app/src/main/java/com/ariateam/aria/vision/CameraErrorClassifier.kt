package com.ariateam.aria.vision

import androidx.camera.core.CameraState

sealed interface CameraFailureKind {
    val isRecoverable: Boolean
    val description: String

    data class TransientInUse(
        val code: Int,
        override val description: String,
    ) : CameraFailureKind {
        override val isRecoverable: Boolean = true
    }

    data class Permission(
        override val description: String = "카메라 권한이 필요합니다.",
    ) : CameraFailureKind {
        override val isRecoverable: Boolean = false
    }

    data class Fatal(
        val code: Int,
        override val description: String,
    ) : CameraFailureKind {
        override val isRecoverable: Boolean = false
    }
}

object CameraErrorClassifier {
    fun classifyStateError(error: CameraState.StateError): CameraFailureKind =
        classifyCode(error.code, error.cause?.message)

    fun classifyCode(code: Int, causeMessage: String? = null): CameraFailureKind {
        val detail = causeMessage?.take(80) ?: ""
        return when (code) {
            CameraState.ERROR_CAMERA_IN_USE ->
                CameraFailureKind.TransientInUse(
                    code = code,
                    description = "카메라를 다른 앱에서 사용 중입니다. 사용이 끝나면 자동으로 다시 연결합니다. ${detail}".trim(),
                )
            CameraState.ERROR_MAX_CAMERAS_IN_USE ->
                CameraFailureKind.TransientInUse(
                    code = code,
                    description = "동시 사용 가능한 카메라 수가 초과되었습니다. 사용이 끝나면 자동으로 다시 연결합니다.",
                )
            CameraState.ERROR_OTHER_RECOVERABLE_ERROR ->
                CameraFailureKind.TransientInUse(
                    code = code,
                    description = "카메라 일시 연결 오류가 발생하여 자동 복구를 시도합니다. ${detail}".trim(),
                )
            CameraState.ERROR_DO_NOT_DISTURB_MODE_ENABLED ->
                CameraFailureKind.TransientInUse(
                    code = code,
                    description = "방해 금지 모드로 인해 카메라 연결이 일시 제한되었습니다.",
                )
            CameraState.ERROR_CAMERA_DISABLED ->
                CameraFailureKind.Fatal(
                    code = code,
                    description = "기기 보안 정책에 의해 카메라가 비활성화되었습니다.",
                )
            CameraState.ERROR_STREAM_CONFIG ->
                CameraFailureKind.Fatal(
                    code = code,
                    description = "카메라 스트림 해상도 구성에 실패했습니다 (${detail}).",
                )
            CameraState.ERROR_CAMERA_FATAL_ERROR ->
                CameraFailureKind.Fatal(
                    code = code,
                    description = "카메라 하드웨어 치명적 오류가 발생했습니다 (${detail}).",
                )
            else ->
                CameraFailureKind.Fatal(
                    code = code,
                    description = "알 수 없는 카메라 오류 (${code}: ${detail}).",
                )
        }
    }

    fun classifyException(throwable: Throwable): CameraFailureKind {
        if (throwable is SecurityException) {
            return CameraFailureKind.Permission("카메라 권한이 거부되었습니다.")
        }
        val name = throwable.javaClass.simpleName
        val message = throwable.message.orEmpty().lowercase()
        if (name.contains("CameraUnavailable", ignoreCase = true) ||
            message.contains("in use") ||
            message.contains("busy") ||
            message.contains("max cameras")
        ) {
            return CameraFailureKind.TransientInUse(
                code = CameraState.ERROR_CAMERA_IN_USE,
                description = "카메라를 다른 앱에서 사용 중입니다 (${name}). 사용이 끝나면 자동으로 다시 연결합니다.",
            )
        }
        return CameraFailureKind.Fatal(
            code = -1,
            description = "카메라 초기화 실패 (${name}: ${throwable.message.orEmpty().take(80)})",
        )
    }
}
