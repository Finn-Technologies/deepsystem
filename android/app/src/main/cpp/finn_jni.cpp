#include <jni.h>
#include <android/log.h>
#include <string>
#include <vector>
#include <mutex>
#include "finn/finn_c.h"

#define TAG "FinnJNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

static JavaVM* g_jvm = nullptr;

jint JNI_OnLoad(JavaVM* vm, void* /*reserved*/) {
    g_jvm = vm;
    LOGI("JNI_OnLoad initialized with VM=%p", vm);
    return JNI_VERSION_1_6;
}

struct CallbackWrapperContext {
    JNIEnv* env;
    jobject callback;
    jmethodID onTokenMethod;
};

static int jni_token_callback(const char* token, void* user_data) {
    auto* ctx = static_cast<CallbackWrapperContext*>(user_data);
    if (!ctx || !ctx->env || !ctx->callback || !ctx->onTokenMethod) {
        return 0; // continue
    }

    jstring jToken = ctx->env->NewStringUTF(token ? token : "");
    jboolean keepGoing = ctx->env->CallBooleanMethod(ctx->callback, ctx->onTokenMethod, jToken);
    ctx->env->DeleteLocalRef(jToken);

    if (ctx->env->ExceptionCheck()) {
        ctx->env->ExceptionDescribe();
        ctx->env->ExceptionClear();
        return 1; // cancel on Java exception
    }

    return keepGoing ? 0 : 1;
}

struct JniLiveContext {
    finn_live_session_t* session = nullptr;
    jobject stateCallback = nullptr;
    jmethodID onStateChangedMethod = nullptr;
    jobject audioCallback = nullptr;
    jmethodID onAudioChunkMethod = nullptr;
    std::mutex cbMutex;

    ~JniLiveContext() {
        if (stateCallback || audioCallback) {
            JNIEnv* env = nullptr;
            bool attached = false;
            if (g_jvm) {
                int res = g_jvm->GetEnv((void**)&env, JNI_VERSION_1_6);
                if (res == JNI_EDETACHED) {
                    if (g_jvm->AttachCurrentThread(&env, nullptr) == 0) attached = true;
                }
                if (env) {
                    if (stateCallback) {
                        env->DeleteGlobalRef(stateCallback);
                        stateCallback = nullptr;
                    }
                    if (audioCallback) {
                        env->DeleteGlobalRef(audioCallback);
                        audioCallback = nullptr;
                    }
                }
                if (attached) g_jvm->DetachCurrentThread();
            }
        }
        if (session) {
            finn_live_destroy(session);
            session = nullptr;
        }
    }
};

static void jni_live_state_cb(int32_t old_s, int32_t new_s, void* user_data) {
    auto* ctx = static_cast<JniLiveContext*>(user_data);
    if (!ctx || !g_jvm) return;

    std::lock_guard<std::mutex> lock(ctx->cbMutex);
    if (!ctx->stateCallback || !ctx->onStateChangedMethod) return;

    JNIEnv* env = nullptr;
    bool attached = false;
    int res = g_jvm->GetEnv((void**)&env, JNI_VERSION_1_6);
    if (res == JNI_EDETACHED) {
        if (g_jvm->AttachCurrentThread(&env, nullptr) == 0) attached = true;
    }
    if (env) {
        env->CallVoidMethod(ctx->stateCallback, ctx->onStateChangedMethod, (jint)old_s, (jint)new_s);
        if (env->ExceptionCheck()) {
            env->ExceptionDescribe();
            env->ExceptionClear();
        }
    }
    if (attached) {
        g_jvm->DetachCurrentThread();
    }
}

static int jni_live_audio_cb(const int16_t* pcm, size_t count, int sample_rate, void* user_data) {
    auto* ctx = static_cast<JniLiveContext*>(user_data);
    if (!ctx || !g_jvm || !pcm || count == 0) return 0;

    std::lock_guard<std::mutex> lock(ctx->cbMutex);
    if (!ctx->audioCallback || !ctx->onAudioChunkMethod) return 0;

    JNIEnv* env = nullptr;
    bool attached = false;
    int res = g_jvm->GetEnv((void**)&env, JNI_VERSION_1_6);
    if (res == JNI_EDETACHED) {
        if (g_jvm->AttachCurrentThread(&env, nullptr) == 0) attached = true;
    }
    int ret = 0;
    if (env) {
        jshortArray jArray = env->NewShortArray(static_cast<jsize>(count));
        if (jArray) {
            env->SetShortArrayRegion(jArray, 0, static_cast<jsize>(count), reinterpret_cast<const jshort*>(pcm));
            jboolean keepGoing = env->CallBooleanMethod(ctx->audioCallback, ctx->onAudioChunkMethod, jArray, (jint)sample_rate);
            env->DeleteLocalRef(jArray);
            if (!keepGoing) ret = 1;
        }
        if (env->ExceptionCheck()) {
            env->ExceptionDescribe();
            env->ExceptionClear();
            ret = 1;
        }
    }
    if (attached) {
        g_jvm->DetachCurrentThread();
    }
    return ret;
}

extern "C" {

JNIEXPORT jlong JNICALL
Java_app_finn_deepsystem_engine_NativeFinnBridge_nativeEngineCreate(
    JNIEnv* /*env*/,
    jobject /*thiz*/,
    jboolean isFake
) {
    finn_engine_t* engine = isFake ? finn_engine_create_fake() : finn_engine_create();
    LOGI("Created engine instance (fake=%d): %p", (int)isFake, engine);
    return reinterpret_cast<jlong>(engine);
}

JNIEXPORT void JNICALL
Java_app_finn_deepsystem_engine_NativeFinnBridge_nativeEngineDestroy(
    JNIEnv* /*env*/,
    jobject /*thiz*/,
    jlong handle
) {
    auto* engine = reinterpret_cast<finn_engine_t*>(handle);
    if (engine) {
        LOGI("Destroying engine instance: %p", engine);
        finn_engine_destroy(engine);
    }
}

JNIEXPORT jint JNICALL
Java_app_finn_deepsystem_engine_NativeFinnBridge_nativeEngineLoad(
    JNIEnv* env,
    jobject /*thiz*/,
    jlong handle,
    jstring modelPath,
    jstring mmprojPath,
    jint nCtx,
    jint nThreads,
    jint nGpuLayers,
    jboolean useMmap
) {
    auto* engine = reinterpret_cast<finn_engine_t*>(handle);
    if (!engine) return -1;

    const char* cModelPath = modelPath ? env->GetStringUTFChars(modelPath, nullptr) : nullptr;
    const char* cMmprojPath = mmprojPath ? env->GetStringUTFChars(mmprojPath, nullptr) : nullptr;

    finn_engine_config_t config{};
    config.model_path = cModelPath;
    config.mmproj_path = cMmprojPath;
    config.n_ctx = nCtx > 0 ? nCtx : 4096;
    config.n_threads = nThreads > 0 ? nThreads : 4;
    config.n_gpu_layers = nGpuLayers;
    config.use_mmap = useMmap ? 1 : 0;

    LOGI("Loading engine model: %s (mmproj: %s, ctx: %d, threads: %d, mmap: %d)",
         config.model_path ? config.model_path : "(null)",
         config.mmproj_path ? config.mmproj_path : "(null)",
         config.n_ctx, config.n_threads, config.use_mmap);

    int res = finn_engine_load(engine, &config);

    if (cModelPath) env->ReleaseStringUTFChars(modelPath, cModelPath);
    if (cMmprojPath) env->ReleaseStringUTFChars(mmprojPath, cMmprojPath);

    return res;
}

JNIEXPORT void JNICALL
Java_app_finn_deepsystem_engine_NativeFinnBridge_nativeEngineUnload(
    JNIEnv* /*env*/,
    jobject /*thiz*/,
    jlong handle
) {
    auto* engine = reinterpret_cast<finn_engine_t*>(handle);
    if (engine) {
        finn_engine_unload(engine);
    }
}

JNIEXPORT jboolean JNICALL
Java_app_finn_deepsystem_engine_NativeFinnBridge_nativeEngineIsLoaded(
    JNIEnv* /*env*/,
    jobject /*thiz*/,
    jlong handle
) {
    auto* engine = reinterpret_cast<finn_engine_t*>(handle);
    if (!engine) return JNI_FALSE;
    return finn_engine_is_loaded(engine) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jint JNICALL
Java_app_finn_deepsystem_engine_NativeFinnBridge_nativeEngineGenerate(
    JNIEnv* env,
    jobject /*thiz*/,
    jlong handle,
    jstring prompt,
    jbyteArray imageBytes,
    jfloat temperature,
    jint maxTokens,
    jobject callback,
    jdoubleArray statsOut
) {
    auto* engine = reinterpret_cast<finn_engine_t*>(handle);
    if (!engine || !prompt) return -1;

    const char* cPrompt = env->GetStringUTFChars(prompt, nullptr);
    if (!cPrompt) return -1;

    CallbackWrapperContext ctx{};
    ctx.env = env;
    ctx.callback = callback;
    if (callback) {
        jclass cbClass = env->GetObjectClass(callback);
        ctx.onTokenMethod = env->GetMethodID(cbClass, "onToken", "(Ljava/lang/String;)Z");
    }

    finn_engine_stats_t stats{};
    int result = -1;

    if (imageBytes != nullptr) {
        jsize imgLen = env->GetArrayLength(imageBytes);
        jbyte* imgData = env->GetByteArrayElements(imageBytes, nullptr);
        if (imgData && imgLen > 0) {
            result = finn_engine_generate_multimodal(
                engine,
                cPrompt,
                reinterpret_cast<const uint8_t*>(imgData),
                static_cast<size_t>(imgLen),
                nullptr,
                0,
                temperature,
                maxTokens,
                callback ? jni_token_callback : nullptr,
                &ctx,
                &stats
            );
            env->ReleaseByteArrayElements(imageBytes, imgData, JNI_ABORT);
        }
    } else {
        result = finn_engine_generate_text(
            engine,
            cPrompt,
            temperature,
            maxTokens,
            callback ? jni_token_callback : nullptr,
            &ctx,
            &stats
        );
    }

    env->ReleaseStringUTFChars(prompt, cPrompt);

    if (statsOut != nullptr) {
        jsize len = env->GetArrayLength(statsOut);
        if (len >= 5) {
            jdouble s[5];
            s[0] = stats.ttft_ms;
            s[1] = stats.tokens_per_second;
            s[2] = static_cast<jdouble>(stats.prompt_tokens);
            s[3] = static_cast<jdouble>(stats.completion_tokens);
            s[4] = stats.total_time_ms;
            env->SetDoubleArrayRegion(statsOut, 0, 5, s);
        }
    }

    return result;
}

JNIEXPORT jint JNICALL
Java_app_finn_deepsystem_engine_NativeFinnBridge_nativeEngineGenerateMultimodal(
    JNIEnv* env,
    jobject /*thiz*/,
    jlong handle,
    jstring prompt,
    jbyteArray imageBytes,
    jbyteArray audioBytes,
    jobjectArray videoFrames,
    jfloat temperature,
    jint maxTokens,
    jobject callback,
    jdoubleArray statsOut
) {
    auto* engine = reinterpret_cast<finn_engine_t*>(handle);
    if (!engine || !prompt) return -1;

    const char* cPrompt = env->GetStringUTFChars(prompt, nullptr);
    if (!cPrompt) return -1;

    CallbackWrapperContext ctx{};
    ctx.env = env;
    ctx.callback = callback;
    if (callback) {
        jclass cbClass = env->GetObjectClass(callback);
        ctx.onTokenMethod = env->GetMethodID(cbClass, "onToken", "(Ljava/lang/String;)Z");
    }

    finn_engine_stats_t stats{};
    int result = -1;

    // Check for video frames
    if (videoFrames != nullptr) {
        jsize nFrames = env->GetArrayLength(videoFrames);
        std::vector<std::vector<uint8_t>> frameBufs;
        std::vector<const uint8_t*> framePtrs;
        std::vector<size_t> frameSizes;

        for (jsize i = 0; i < nFrames; ++i) {
            auto frameArray = static_cast<jbyteArray>(env->GetObjectArrayElement(videoFrames, i));
            if (frameArray) {
                jsize len = env->GetArrayLength(frameArray);
                jbyte* bytes = env->GetByteArrayElements(frameArray, nullptr);
                if (bytes && len > 0) {
                    frameBufs.emplace_back(reinterpret_cast<const uint8_t*>(bytes), reinterpret_cast<const uint8_t*>(bytes) + len);
                }
                if (bytes) env->ReleaseByteArrayElements(frameArray, bytes, JNI_ABORT);
                env->DeleteLocalRef(frameArray);
            }
        }

        for (const auto& fb : frameBufs) {
            framePtrs.push_back(fb.data());
            frameSizes.push_back(fb.size());
        }

        jbyte* aData = nullptr;
        jsize aLen = 0;
        if (audioBytes) {
            aLen = env->GetArrayLength(audioBytes);
            aData = env->GetByteArrayElements(audioBytes, nullptr);
        }

        result = finn_engine_generate_video(
            engine,
            cPrompt,
            framePtrs.data(),
            frameSizes.data(),
            framePtrs.size(),
            reinterpret_cast<const uint8_t*>(aData),
            static_cast<size_t>(aLen),
            temperature,
            maxTokens,
            callback ? jni_token_callback : nullptr,
            &ctx,
            &stats
        );

        if (aData) env->ReleaseByteArrayElements(audioBytes, aData, JNI_ABORT);
    } else if (imageBytes != nullptr || audioBytes != nullptr) {
        jbyte* imgData = nullptr;
        jsize imgLen = 0;
        if (imageBytes) {
            imgLen = env->GetArrayLength(imageBytes);
            imgData = env->GetByteArrayElements(imageBytes, nullptr);
        }

        jbyte* audData = nullptr;
        jsize audLen = 0;
        if (audioBytes) {
            audLen = env->GetArrayLength(audioBytes);
            audData = env->GetByteArrayElements(audioBytes, nullptr);
        }

        result = finn_engine_generate_multimodal(
            engine,
            cPrompt,
            reinterpret_cast<const uint8_t*>(imgData),
            static_cast<size_t>(imgLen),
            reinterpret_cast<const uint8_t*>(audData),
            static_cast<size_t>(audLen),
            temperature,
            maxTokens,
            callback ? jni_token_callback : nullptr,
            &ctx,
            &stats
        );

        if (imgData) env->ReleaseByteArrayElements(imageBytes, imgData, JNI_ABORT);
        if (audData) env->ReleaseByteArrayElements(audioBytes, audData, JNI_ABORT);
    } else {
        result = finn_engine_generate_text(
            engine,
            cPrompt,
            temperature,
            maxTokens,
            callback ? jni_token_callback : nullptr,
            &ctx,
            &stats
        );
    }

    env->ReleaseStringUTFChars(prompt, cPrompt);

    if (statsOut != nullptr) {
        jsize len = env->GetArrayLength(statsOut);
        if (len >= 5) {
            jdouble s[5];
            s[0] = stats.ttft_ms;
            s[1] = stats.tokens_per_second;
            s[2] = static_cast<jdouble>(stats.prompt_tokens);
            s[3] = static_cast<jdouble>(stats.completion_tokens);
            s[4] = stats.total_time_ms;
            env->SetDoubleArrayRegion(statsOut, 0, 5, s);
        }
    }

    return result;
}

JNIEXPORT jint JNICALL
Java_app_finn_deepsystem_engine_NativeFinnBridge_nativeEngineSpeak(
    JNIEnv* env,
    jobject /*thiz*/,
    jlong handle,
    jstring text,
    jstring voice,
    jobject callback
) {
    auto* engine = reinterpret_cast<finn_engine_t*>(handle);
    if (!engine || !text) return -1;

    const char* cText = env->GetStringUTFChars(text, nullptr);
    const char* cVoice = voice ? env->GetStringUTFChars(voice, nullptr) : nullptr;

    jclass cbClass = callback ? env->GetObjectClass(callback) : nullptr;
    jmethodID onChunkMethod = cbClass ? env->GetMethodID(cbClass, "onAudioChunk", "([SI)Z") : nullptr;

    struct SpeakContext {
        JNIEnv* env;
        jobject callback;
        jmethodID method;
    } s_ctx{env, callback, onChunkMethod};

    auto audio_cb = [](const int16_t* pcm, size_t count, int sample_rate, void* u) -> int {
        auto* sc = static_cast<SpeakContext*>(u);
        if (!sc || !sc->env || !sc->callback || !sc->method || !pcm || count == 0) return 0;

        jshortArray jArray = sc->env->NewShortArray(static_cast<jsize>(count));
        if (!jArray) return 0;
        sc->env->SetShortArrayRegion(jArray, 0, static_cast<jsize>(count), reinterpret_cast<const jshort*>(pcm));
        jboolean keepGoing = sc->env->CallBooleanMethod(sc->callback, sc->method, jArray, (jint)sample_rate);
        sc->env->DeleteLocalRef(jArray);

        if (sc->env->ExceptionCheck()) {
            sc->env->ExceptionDescribe();
            sc->env->ExceptionClear();
            return 1;
        }
        return keepGoing ? 0 : 1;
    };

    int res = finn_engine_speak(engine, cText, cVoice, callback ? audio_cb : nullptr, &s_ctx);

    if (cText) env->ReleaseStringUTFChars(text, cText);
    if (cVoice) env->ReleaseStringUTFChars(voice, cVoice);

    return res;
}

JNIEXPORT void JNICALL
Java_app_finn_deepsystem_engine_NativeFinnBridge_nativeEngineCancel(
    JNIEnv* /*env*/,
    jobject /*thiz*/,
    jlong handle
) {
    auto* engine = reinterpret_cast<finn_engine_t*>(handle);
    if (engine) {
        finn_engine_cancel(engine);
    }
}

JNIEXPORT jboolean JNICALL
Java_app_finn_deepsystem_engine_NativeFinnBridge_nativeEngineIsCancelled(
    JNIEnv* /*env*/,
    jobject /*thiz*/,
    jlong handle
) {
    auto* engine = reinterpret_cast<finn_engine_t*>(handle);
    if (!engine) return JNI_FALSE;
    return finn_engine_is_cancelled(engine) ? JNI_TRUE : JNI_FALSE;
}

/* Live Session JNI */

JNIEXPORT jlong JNICALL
Java_app_finn_deepsystem_engine_NativeFinnBridge_nativeLiveCreate(
    JNIEnv* env,
    jobject /*thiz*/,
    jlong engineHandle,
    jfloat vadThreshold,
    jint silenceTimeoutMs,
    jstring voice,
    jstring systemPrompt
) {
    auto* engine = reinterpret_cast<finn_engine_t*>(engineHandle);
    if (!engine) return 0;

    const char* cVoice = voice ? env->GetStringUTFChars(voice, nullptr) : nullptr;
    const char* cPrompt = systemPrompt ? env->GetStringUTFChars(systemPrompt, nullptr) : nullptr;

    finn_live_config_t cfg{};
    cfg.vad_energy_threshold = vadThreshold > 0.0f ? vadThreshold : 450.0f;
    cfg.vad_silence_timeout_ms = silenceTimeoutMs > 0 ? silenceTimeoutMs : 600;
    cfg.voice = cVoice ? cVoice : "female";
    cfg.system_prompt = cPrompt ? cPrompt : "";

    finn_live_session_t* session = finn_live_create(engine, &cfg);

    if (cVoice) env->ReleaseStringUTFChars(voice, cVoice);
    if (cPrompt) env->ReleaseStringUTFChars(systemPrompt, cPrompt);

    if (!session) return 0;

    auto* ctx = new JniLiveContext();
    ctx->session = session;
    LOGI("Created native LiveSession: %p", ctx);
    return reinterpret_cast<jlong>(ctx);
}

JNIEXPORT void JNICALL
Java_app_finn_deepsystem_engine_NativeFinnBridge_nativeLiveDestroy(
    JNIEnv* /*env*/,
    jobject /*thiz*/,
    jlong handle
) {
    auto* ctx = reinterpret_cast<JniLiveContext*>(handle);
    if (ctx) {
        LOGI("Destroying native LiveSession: %p", ctx);
        delete ctx;
    }
}

JNIEXPORT void JNICALL
Java_app_finn_deepsystem_engine_NativeFinnBridge_nativeLiveStart(
    JNIEnv* /*env*/,
    jobject /*thiz*/,
    jlong handle
) {
    auto* ctx = reinterpret_cast<JniLiveContext*>(handle);
    if (ctx && ctx->session) {
        finn_live_start(ctx->session);
    }
}

JNIEXPORT void JNICALL
Java_app_finn_deepsystem_engine_NativeFinnBridge_nativeLiveStop(
    JNIEnv* /*env*/,
    jobject /*thiz*/,
    jlong handle
) {
    auto* ctx = reinterpret_cast<JniLiveContext*>(handle);
    if (ctx && ctx->session) {
        finn_live_stop(ctx->session);
    }
}

JNIEXPORT void JNICALL
Java_app_finn_deepsystem_engine_NativeFinnBridge_nativeLiveFeedAudio(
    JNIEnv* env,
    jobject /*thiz*/,
    jlong handle,
    jshortArray pcmSamples,
    jint offset,
    jint length
) {
    auto* ctx = reinterpret_cast<JniLiveContext*>(handle);
    if (!ctx || !ctx->session || !pcmSamples || length <= 0) return;

    jshort* samples = env->GetShortArrayElements(pcmSamples, nullptr);
    if (samples) {
        finn_live_feed_audio(ctx->session, reinterpret_cast<const int16_t*>(samples + offset), static_cast<size_t>(length));
        env->ReleaseShortArrayElements(pcmSamples, samples, JNI_ABORT);
    }
}

JNIEXPORT void JNICALL
Java_app_finn_deepsystem_engine_NativeFinnBridge_nativeLiveFeedCameraFrame(
    JNIEnv* env,
    jobject /*thiz*/,
    jlong handle,
    jbyteArray frameBytes
) {
    auto* ctx = reinterpret_cast<JniLiveContext*>(handle);
    if (!ctx || !ctx->session) return;

    if (frameBytes != nullptr) {
        jsize len = env->GetArrayLength(frameBytes);
        jbyte* data = env->GetByteArrayElements(frameBytes, nullptr);
        if (data && len > 0) {
            finn_live_feed_camera_frame(ctx->session, reinterpret_cast<const uint8_t*>(data), static_cast<size_t>(len));
            env->ReleaseByteArrayElements(frameBytes, data, JNI_ABORT);
        }
    } else {
        finn_live_feed_camera_frame(ctx->session, nullptr, 0);
    }
}

JNIEXPORT void JNICALL
Java_app_finn_deepsystem_engine_NativeFinnBridge_nativeLiveSetCallbacks(
    JNIEnv* env,
    jobject /*thiz*/,
    jlong handle,
    jobject stateCallback,
    jobject audioCallback
) {
    auto* ctx = reinterpret_cast<JniLiveContext*>(handle);
    if (!ctx || !ctx->session) return;

    std::lock_guard<std::mutex> lock(ctx->cbMutex);

    if (ctx->stateCallback) {
        env->DeleteGlobalRef(ctx->stateCallback);
        ctx->stateCallback = nullptr;
    }
    if (stateCallback) {
        ctx->stateCallback = env->NewGlobalRef(stateCallback);
        jclass scClass = env->GetObjectClass(stateCallback);
        ctx->onStateChangedMethod = env->GetMethodID(scClass, "onStateChanged", "(II)V");
        finn_live_set_state_callback(ctx->session, jni_live_state_cb, ctx);
    } else {
        finn_live_set_state_callback(ctx->session, nullptr, nullptr);
    }

    if (ctx->audioCallback) {
        env->DeleteGlobalRef(ctx->audioCallback);
        ctx->audioCallback = nullptr;
    }
    if (audioCallback) {
        ctx->audioCallback = env->NewGlobalRef(audioCallback);
        jclass acClass = env->GetObjectClass(audioCallback);
        ctx->onAudioChunkMethod = env->GetMethodID(acClass, "onAudioChunk", "([SI)Z");
        finn_live_set_audio_callback(ctx->session, jni_live_audio_cb, ctx);
    } else {
        finn_live_set_audio_callback(ctx->session, nullptr, nullptr);
    }
}

JNIEXPORT jint JNICALL
Java_app_finn_deepsystem_engine_NativeFinnBridge_nativeLiveGetState(
    JNIEnv* /*env*/,
    jobject /*thiz*/,
    jlong handle
) {
    auto* ctx = reinterpret_cast<JniLiveContext*>(handle);
    if (!ctx || !ctx->session) return 0;
    return finn_live_get_state(ctx->session);
}

JNIEXPORT jdouble JNICALL
Java_app_finn_deepsystem_engine_NativeFinnBridge_nativeLiveGetLastLatencyMs(
    JNIEnv* /*env*/,
    jobject /*thiz*/,
    jlong handle
) {
    auto* ctx = reinterpret_cast<JniLiveContext*>(handle);
    if (!ctx || !ctx->session) return 0.0;
    return finn_live_get_last_latency_ms(ctx->session);
}

JNIEXPORT void JNICALL
Java_app_finn_deepsystem_engine_NativeFinnBridge_nativeLiveTriggerBargeIn(
    JNIEnv* /*env*/,
    jobject /*thiz*/,
    jlong handle
) {
    auto* ctx = reinterpret_cast<JniLiveContext*>(handle);
    if (ctx && ctx->session) {
        finn_live_trigger_barge_in(ctx->session);
    }
}

JNIEXPORT void JNICALL
Java_app_finn_deepsystem_engine_NativeFinnBridge_nativeLiveTriggerEndOfSpeech(
    JNIEnv* /*env*/,
    jobject /*thiz*/,
    jlong handle
) {
    auto* ctx = reinterpret_cast<JniLiveContext*>(handle);
    if (ctx && ctx->session) {
        finn_live_trigger_end_of_speech(ctx->session);
    }
}

/* Storage JNI */

JNIEXPORT jlong JNICALL
Java_app_finn_deepsystem_engine_NativeFinnBridge_nativeStorageCreate(
    JNIEnv* /*env*/,
    jobject /*thiz*/
) {
    finn_storage_t* storage = finn_storage_create();
    return reinterpret_cast<jlong>(storage);
}

JNIEXPORT void JNICALL
Java_app_finn_deepsystem_engine_NativeFinnBridge_nativeStorageDestroy(
    JNIEnv* /*env*/,
    jobject /*thiz*/,
    jlong handle
) {
    auto* storage = reinterpret_cast<finn_storage_t*>(handle);
    if (storage) {
        finn_storage_destroy(storage);
    }
}

JNIEXPORT jint JNICALL
Java_app_finn_deepsystem_engine_NativeFinnBridge_nativeStorageOpen(
    JNIEnv* env,
    jobject /*thiz*/,
    jlong handle,
    jstring dbPath
) {
    auto* storage = reinterpret_cast<finn_storage_t*>(handle);
    if (!storage || !dbPath) return -1;
    const char* cDbPath = env->GetStringUTFChars(dbPath, nullptr);
    int res = finn_storage_open(storage, cDbPath);
    env->ReleaseStringUTFChars(dbPath, cDbPath);
    return res;
}

JNIEXPORT void JNICALL
Java_app_finn_deepsystem_engine_NativeFinnBridge_nativeStorageClose(
    JNIEnv* /*env*/,
    jobject /*thiz*/,
    jlong handle
) {
    auto* storage = reinterpret_cast<finn_storage_t*>(handle);
    if (storage) {
        finn_storage_close(storage);
    }
}

JNIEXPORT jint JNICALL
Java_app_finn_deepsystem_engine_NativeFinnBridge_nativeStorageCreateConversation(
    JNIEnv* env,
    jobject /*thiz*/,
    jlong handle,
    jstring id,
    jstring title
) {
    auto* storage = reinterpret_cast<finn_storage_t*>(handle);
    if (!storage || !id || !title) return -1;
    const char* cId = env->GetStringUTFChars(id, nullptr);
    const char* cTitle = env->GetStringUTFChars(title, nullptr);
    int res = finn_storage_create_conversation(storage, cId, cTitle);
    env->ReleaseStringUTFChars(id, cId);
    env->ReleaseStringUTFChars(title, cTitle);
    return res;
}

JNIEXPORT jint JNICALL
Java_app_finn_deepsystem_engine_NativeFinnBridge_nativeStorageDeleteConversation(
    JNIEnv* env,
    jobject /*thiz*/,
    jlong handle,
    jstring id
) {
    auto* storage = reinterpret_cast<finn_storage_t*>(handle);
    if (!storage || !id) return -1;
    const char* cId = env->GetStringUTFChars(id, nullptr);
    int res = finn_storage_delete_conversation(storage, cId);
    env->ReleaseStringUTFChars(id, cId);
    return res;
}

JNIEXPORT jint JNICALL
Java_app_finn_deepsystem_engine_NativeFinnBridge_nativeStorageAddMessage(
    JNIEnv* env,
    jobject /*thiz*/,
    jlong handle,
    jstring convId,
    jstring role,
    jstring content
) {
    auto* storage = reinterpret_cast<finn_storage_t*>(handle);
    if (!storage || !convId || !role || !content) return -1;
    const char* cConvId = env->GetStringUTFChars(convId, nullptr);
    const char* cRole = env->GetStringUTFChars(role, nullptr);
    const char* cContent = env->GetStringUTFChars(content, nullptr);
    int res = finn_storage_add_message(storage, cConvId, cRole, cContent);
    env->ReleaseStringUTFChars(convId, cConvId);
    env->ReleaseStringUTFChars(role, cRole);
    env->ReleaseStringUTFChars(content, cContent);
    return res;
}

JNIEXPORT jstring JNICALL
Java_app_finn_deepsystem_engine_NativeFinnBridge_nativeStorageListConversationsJson(
    JNIEnv* env,
    jobject /*thiz*/,
    jlong handle
) {
    auto* storage = reinterpret_cast<finn_storage_t*>(handle);
    if (!storage) return nullptr;
    char* json = finn_storage_list_conversations_json(storage);
    if (!json) return nullptr;
    jstring result = env->NewStringUTF(json);
    finn_free_string(json);
    return result;
}

JNIEXPORT jstring JNICALL
Java_app_finn_deepsystem_engine_NativeFinnBridge_nativeStorageGetMessagesJson(
    JNIEnv* env,
    jobject /*thiz*/,
    jlong handle,
    jstring convId
) {
    auto* storage = reinterpret_cast<finn_storage_t*>(handle);
    if (!storage || !convId) return nullptr;
    const char* cConvId = env->GetStringUTFChars(convId, nullptr);
    char* json = finn_storage_get_messages_json(storage, cConvId);
    env->ReleaseStringUTFChars(convId, cConvId);
    if (!json) return nullptr;
    jstring result = env->NewStringUTF(json);
    finn_free_string(json);
    return result;
}

/* JNI Notes API */

JNIEXPORT jint JNICALL
Java_app_finn_deepsystem_engine_NativeFinnBridge_nativeStorageCreateNote(
    JNIEnv* env,
    jobject /*thiz*/,
    jlong handle,
    jstring id,
    jstring title,
    jstring content,
    jstring folder,
    jstring tags,
    jboolean isPinned,
    jstring mediaPath,
    jstring mediaType,
    jstring aiSummary,
    jstring aiTranscript
) {
    auto* storage = reinterpret_cast<finn_storage_t*>(handle);
    if (!storage || !id) return -1;

    const char* cId = env->GetStringUTFChars(id, nullptr);
    const char* cTitle = title ? env->GetStringUTFChars(title, nullptr) : "";
    const char* cContent = content ? env->GetStringUTFChars(content, nullptr) : "";
    const char* cFolder = folder ? env->GetStringUTFChars(folder, nullptr) : "";
    const char* cTags = tags ? env->GetStringUTFChars(tags, nullptr) : "";
    const char* cMediaPath = mediaPath ? env->GetStringUTFChars(mediaPath, nullptr) : "";
    const char* cMediaType = mediaType ? env->GetStringUTFChars(mediaType, nullptr) : "none";
    const char* cAiSummary = aiSummary ? env->GetStringUTFChars(aiSummary, nullptr) : "";
    const char* cAiTranscript = aiTranscript ? env->GetStringUTFChars(aiTranscript, nullptr) : "";

    int res = finn_storage_create_note(
        storage, cId, cTitle, cContent, cFolder, cTags,
        isPinned ? 1 : 0, cMediaPath, cMediaType, cAiSummary, cAiTranscript
    );

    env->ReleaseStringUTFChars(id, cId);
    if (title) env->ReleaseStringUTFChars(title, cTitle);
    if (content) env->ReleaseStringUTFChars(content, cContent);
    if (folder) env->ReleaseStringUTFChars(folder, cFolder);
    if (tags) env->ReleaseStringUTFChars(tags, cTags);
    if (mediaPath) env->ReleaseStringUTFChars(mediaPath, cMediaPath);
    if (mediaType) env->ReleaseStringUTFChars(mediaType, cMediaType);
    if (aiSummary) env->ReleaseStringUTFChars(aiSummary, cAiSummary);
    if (aiTranscript) env->ReleaseStringUTFChars(aiTranscript, cAiTranscript);

    return res;
}

JNIEXPORT jint JNICALL
Java_app_finn_deepsystem_engine_NativeFinnBridge_nativeStorageUpdateNote(
    JNIEnv* env,
    jobject /*thiz*/,
    jlong handle,
    jstring id,
    jstring title,
    jstring content,
    jstring folder,
    jstring tags,
    jboolean isPinned,
    jstring mediaPath,
    jstring mediaType,
    jstring aiSummary,
    jstring aiTranscript
) {
    auto* storage = reinterpret_cast<finn_storage_t*>(handle);
    if (!storage || !id) return -1;

    const char* cId = env->GetStringUTFChars(id, nullptr);
    const char* cTitle = title ? env->GetStringUTFChars(title, nullptr) : "";
    const char* cContent = content ? env->GetStringUTFChars(content, nullptr) : "";
    const char* cFolder = folder ? env->GetStringUTFChars(folder, nullptr) : "";
    const char* cTags = tags ? env->GetStringUTFChars(tags, nullptr) : "";
    const char* cMediaPath = mediaPath ? env->GetStringUTFChars(mediaPath, nullptr) : "";
    const char* cMediaType = mediaType ? env->GetStringUTFChars(mediaType, nullptr) : "none";
    const char* cAiSummary = aiSummary ? env->GetStringUTFChars(aiSummary, nullptr) : "";
    const char* cAiTranscript = aiTranscript ? env->GetStringUTFChars(aiTranscript, nullptr) : "";

    int res = finn_storage_update_note(
        storage, cId, cTitle, cContent, cFolder, cTags,
        isPinned ? 1 : 0, cMediaPath, cMediaType, cAiSummary, cAiTranscript
    );

    env->ReleaseStringUTFChars(id, cId);
    if (title) env->ReleaseStringUTFChars(title, cTitle);
    if (content) env->ReleaseStringUTFChars(content, cContent);
    if (folder) env->ReleaseStringUTFChars(folder, cFolder);
    if (tags) env->ReleaseStringUTFChars(tags, cTags);
    if (mediaPath) env->ReleaseStringUTFChars(mediaPath, cMediaPath);
    if (mediaType) env->ReleaseStringUTFChars(mediaType, cMediaType);
    if (aiSummary) env->ReleaseStringUTFChars(aiSummary, cAiSummary);
    if (aiTranscript) env->ReleaseStringUTFChars(aiTranscript, cAiTranscript);

    return res;
}

JNIEXPORT jint JNICALL
Java_app_finn_deepsystem_engine_NativeFinnBridge_nativeStorageDeleteNote(
    JNIEnv* env,
    jobject /*thiz*/,
    jlong handle,
    jstring id
) {
    auto* storage = reinterpret_cast<finn_storage_t*>(handle);
    if (!storage || !id) return -1;
    const char* cId = env->GetStringUTFChars(id, nullptr);
    int res = finn_storage_delete_note(storage, cId);
    env->ReleaseStringUTFChars(id, cId);
    return res;
}

JNIEXPORT jstring JNICALL
Java_app_finn_deepsystem_engine_NativeFinnBridge_nativeStorageGetNoteJson(
    JNIEnv* env,
    jobject /*thiz*/,
    jlong handle,
    jstring id
) {
    auto* storage = reinterpret_cast<finn_storage_t*>(handle);
    if (!storage || !id) return nullptr;
    const char* cId = env->GetStringUTFChars(id, nullptr);
    char* json = finn_storage_get_note_json(storage, cId);
    env->ReleaseStringUTFChars(id, cId);
    if (!json) return nullptr;
    jstring result = env->NewStringUTF(json);
    finn_free_string(json);
    return result;
}

JNIEXPORT jstring JNICALL
Java_app_finn_deepsystem_engine_NativeFinnBridge_nativeStorageListNotesJson(
    JNIEnv* env,
    jobject /*thiz*/,
    jlong handle,
    jstring folder,
    jstring tag
) {
    auto* storage = reinterpret_cast<finn_storage_t*>(handle);
    if (!storage) return nullptr;
    const char* cFolder = folder ? env->GetStringUTFChars(folder, nullptr) : nullptr;
    const char* cTag = tag ? env->GetStringUTFChars(tag, nullptr) : nullptr;
    char* json = finn_storage_list_notes_json(storage, cFolder, cTag);
    if (folder) env->ReleaseStringUTFChars(folder, cFolder);
    if (tag) env->ReleaseStringUTFChars(tag, cTag);
    if (!json) return nullptr;
    jstring result = env->NewStringUTF(json);
    finn_free_string(json);
    return result;
}

JNIEXPORT jstring JNICALL
Java_app_finn_deepsystem_engine_NativeFinnBridge_nativeStorageSearchNotesJson(
    JNIEnv* env,
    jobject /*thiz*/,
    jlong handle,
    jstring query
) {
    auto* storage = reinterpret_cast<finn_storage_t*>(handle);
    if (!storage) return nullptr;
    const char* cQuery = query ? env->GetStringUTFChars(query, nullptr) : nullptr;
    char* json = finn_storage_search_notes_json(storage, cQuery);
    if (query) env->ReleaseStringUTFChars(query, cQuery);
    if (!json) return nullptr;
    jstring result = env->NewStringUTF(json);
    finn_free_string(json);
    return result;
}

/* JNI Mini Apps API */

JNIEXPORT jint JNICALL
Java_app_finn_deepsystem_engine_NativeFinnBridge_nativeStorageCreateMiniApp(
    JNIEnv* env,
    jobject /*thiz*/,
    jlong handle,
    jstring id,
    jstring title,
    jstring description,
    jstring htmlContent,
    jstring prompt,
    jboolean isPinned
) {
    auto* storage = reinterpret_cast<finn_storage_t*>(handle);
    if (!storage || !id) return -1;

    const char* cId = env->GetStringUTFChars(id, nullptr);
    const char* cTitle = title ? env->GetStringUTFChars(title, nullptr) : "";
    const char* cDesc = description ? env->GetStringUTFChars(description, nullptr) : "";
    const char* cHtml = htmlContent ? env->GetStringUTFChars(htmlContent, nullptr) : "";
    const char* cPrompt = prompt ? env->GetStringUTFChars(prompt, nullptr) : "";

    int res = finn_storage_create_mini_app(storage, cId, cTitle, cDesc, cHtml, cPrompt, isPinned ? 1 : 0);

    env->ReleaseStringUTFChars(id, cId);
    if (title) env->ReleaseStringUTFChars(title, cTitle);
    if (description) env->ReleaseStringUTFChars(description, cDesc);
    if (htmlContent) env->ReleaseStringUTFChars(htmlContent, cHtml);
    if (prompt) env->ReleaseStringUTFChars(prompt, cPrompt);

    return res;
}

JNIEXPORT jint JNICALL
Java_app_finn_deepsystem_engine_NativeFinnBridge_nativeStorageUpdateMiniApp(
    JNIEnv* env,
    jobject /*thiz*/,
    jlong handle,
    jstring id,
    jstring title,
    jstring description,
    jstring htmlContent,
    jstring prompt,
    jboolean isPinned
) {
    auto* storage = reinterpret_cast<finn_storage_t*>(handle);
    if (!storage || !id) return -1;

    const char* cId = env->GetStringUTFChars(id, nullptr);
    const char* cTitle = title ? env->GetStringUTFChars(title, nullptr) : "";
    const char* cDesc = description ? env->GetStringUTFChars(description, nullptr) : "";
    const char* cHtml = htmlContent ? env->GetStringUTFChars(htmlContent, nullptr) : "";
    const char* cPrompt = prompt ? env->GetStringUTFChars(prompt, nullptr) : "";

    int res = finn_storage_update_mini_app(storage, cId, cTitle, cDesc, cHtml, cPrompt, isPinned ? 1 : 0);

    env->ReleaseStringUTFChars(id, cId);
    if (title) env->ReleaseStringUTFChars(title, cTitle);
    if (description) env->ReleaseStringUTFChars(description, cDesc);
    if (htmlContent) env->ReleaseStringUTFChars(htmlContent, cHtml);
    if (prompt) env->ReleaseStringUTFChars(prompt, cPrompt);

    return res;
}

JNIEXPORT jint JNICALL
Java_app_finn_deepsystem_engine_NativeFinnBridge_nativeStorageDeleteMiniApp(
    JNIEnv* env,
    jobject /*thiz*/,
    jlong handle,
    jstring id
) {
    auto* storage = reinterpret_cast<finn_storage_t*>(handle);
    if (!storage || !id) return -1;
    const char* cId = env->GetStringUTFChars(id, nullptr);
    int res = finn_storage_delete_mini_app(storage, cId);
    env->ReleaseStringUTFChars(id, cId);
    return res;
}

JNIEXPORT jstring JNICALL
Java_app_finn_deepsystem_engine_NativeFinnBridge_nativeStorageGetMiniAppJson(
    JNIEnv* env,
    jobject /*thiz*/,
    jlong handle,
    jstring id
) {
    auto* storage = reinterpret_cast<finn_storage_t*>(handle);
    if (!storage || !id) return nullptr;
    const char* cId = env->GetStringUTFChars(id, nullptr);
    char* json = finn_storage_get_mini_app_json(storage, cId);
    env->ReleaseStringUTFChars(id, cId);
    if (!json) return nullptr;
    jstring result = env->NewStringUTF(json);
    finn_free_string(json);
    return result;
}

JNIEXPORT jstring JNICALL
Java_app_finn_deepsystem_engine_NativeFinnBridge_nativeStorageListMiniAppsJson(
    JNIEnv* env,
    jobject /*thiz*/,
    jlong handle
) {
    auto* storage = reinterpret_cast<finn_storage_t*>(handle);
    if (!storage) return nullptr;
    char* json = finn_storage_list_mini_apps_json(storage);
    if (!json) return nullptr;
    jstring result = env->NewStringUTF(json);
    finn_free_string(json);
    return result;
}

JNIEXPORT jint JNICALL
Java_app_finn_deepsystem_engine_NativeFinnBridge_nativeStorageSetMiniAppKv(
    JNIEnv* env,
    jobject /*thiz*/,
    jlong handle,
    jstring appId,
    jstring key,
    jstring value
) {
    auto* storage = reinterpret_cast<finn_storage_t*>(handle);
    if (!storage || !appId || !key || !value) return -1;
    const char* cAppId = env->GetStringUTFChars(appId, nullptr);
    const char* cKey = env->GetStringUTFChars(key, nullptr);
    const char* cValue = env->GetStringUTFChars(value, nullptr);

    int res = finn_storage_set_mini_app_kv(storage, cAppId, cKey, cValue);

    env->ReleaseStringUTFChars(appId, cAppId);
    env->ReleaseStringUTFChars(key, cKey);
    env->ReleaseStringUTFChars(value, cValue);
    return res;
}

JNIEXPORT jstring JNICALL
Java_app_finn_deepsystem_engine_NativeFinnBridge_nativeStorageGetMiniAppKv(
    JNIEnv* env,
    jobject /*thiz*/,
    jlong handle,
    jstring appId,
    jstring key
) {
    auto* storage = reinterpret_cast<finn_storage_t*>(handle);
    if (!storage || !appId || !key) return nullptr;
    const char* cAppId = env->GetStringUTFChars(appId, nullptr);
    const char* cKey = env->GetStringUTFChars(key, nullptr);

    char* val = finn_storage_get_mini_app_kv(storage, cAppId, cKey);

    env->ReleaseStringUTFChars(appId, cAppId);
    env->ReleaseStringUTFChars(key, cKey);

    if (!val) return nullptr;
    jstring result = env->NewStringUTF(val);
    finn_free_string(val);
    return result;
}

JNIEXPORT jint JNICALL
Java_app_finn_deepsystem_engine_NativeFinnBridge_nativeStorageDeleteMiniAppKv(
    JNIEnv* env,
    jobject /*thiz*/,
    jlong handle,
    jstring appId,
    jstring key
) {
    auto* storage = reinterpret_cast<finn_storage_t*>(handle);
    if (!storage || !appId || !key) return -1;
    const char* cAppId = env->GetStringUTFChars(appId, nullptr);
    const char* cKey = env->GetStringUTFChars(key, nullptr);

    int res = finn_storage_delete_mini_app_kv(storage, cAppId, cKey);

    env->ReleaseStringUTFChars(appId, cAppId);
    env->ReleaseStringUTFChars(key, cKey);
    return res;
}

} // extern "C"
