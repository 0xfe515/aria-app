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
    oneway void downloadModel(boolean mobileData);
    oneway void cancelModelDownload();
    oneway void deleteModel();
}
