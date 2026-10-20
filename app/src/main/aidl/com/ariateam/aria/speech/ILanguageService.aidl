package com.ariateam.aria.speech;
import com.ariateam.aria.speech.ILanguageCallback;
interface ILanguageService {
    oneway void prepare(String path, ILanguageCallback callback);
    oneway void interpret(long requestId, String prompt, ILanguageCallback callback);
    oneway void release();
}
