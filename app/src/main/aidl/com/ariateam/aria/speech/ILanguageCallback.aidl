package com.ariateam.aria.speech;
oneway interface ILanguageCallback {
    void onResult(long requestId, String result, String error);
}
