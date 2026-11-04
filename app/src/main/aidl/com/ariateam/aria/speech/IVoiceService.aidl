package com.ariateam.aria.speech;
import android.os.Bundle;
import com.ariateam.aria.speech.IVoiceCallback;

interface IVoiceService {
    Bundle getState();
    void registerCallback(IVoiceCallback callback);
    void unregisterCallback(IVoiceCallback callback);
    oneway void beginDictation();
    oneway void finishDictation();
    oneway void setAudibleFeedback(boolean enabled);
    oneway void stop();
    oneway void setUiVisible(boolean visible);
    oneway void setUiScreen(String screen);
    oneway void consumeNavigation(long requestId);
    oneway void consumeCloudQuestion(long requestId);
    oneway void speakCloudResponse(long questionId, String text);
    oneway void reportCloudError(long questionId, String errorText);
    oneway void cancelCloudQuestion(long questionId);
    oneway void speakHazardWarning(String message);
    oneway void postHazardAlert(long session, long sequence, long timestampNanos, String message);
    oneway void preemptSpeech();
    boolean isServiceActive();
    String getServiceSessionId();
}
