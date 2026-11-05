#include <jni.h>
#include <android/log.h>
#include <string>
#include <vector>
#include "finn/finn_c.h"

#define TAG "FinnJNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

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

} // extern "C"
