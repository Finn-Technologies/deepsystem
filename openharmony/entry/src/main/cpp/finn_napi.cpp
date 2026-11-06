#include "napi/native_api.h"

#include "finn/finn_c.h"
#include "finn/finn_c_api.h"

#include <cstring>
#include <string>
#include <vector>

static finn_engine_t* g_engine = nullptr;
static finn_storage_t* g_storage = nullptr;
static finn_agent_dispatcher_t* g_dispatcher = nullptr;

struct StreamCtx {
    napi_env env;
    napi_value callback;
};

static int stream_trampoline(const char* token, void* user_data) {
    StreamCtx* ctx = static_cast<StreamCtx*>(user_data);
    napi_value argv;
    napi_create_string_utf8(ctx->env, token, NAPI_AUTO_LENGTH, &argv);
    napi_value global;
    napi_get_global(ctx->env, &global);
    napi_value result;
    napi_call_function(ctx->env, global, ctx->callback, 1, &argv, &result);
    return 0;
}

static napi_value InitEngine(napi_env env, napi_callback_info info) {
    size_t argc = 3;
    napi_value args[3];
    napi_get_cb_info(env, info, &argc, args, nullptr, nullptr);

    char buf[4096] = {0};
    size_t len = 0;
    napi_get_value_string_utf8(env, args[0], buf, sizeof(buf), &len);

    int32_t nCtx = 2048;
    int32_t nThreads = 4;
    napi_get_value_int32(env, args[1], &nCtx);
    napi_get_value_int32(env, args[2], &nThreads);

    if (!g_engine) g_engine = finn_engine_create();
    finn_engine_config_t cfg{};
    cfg.model_path = buf;
    cfg.n_ctx = nCtx;
    cfg.n_threads = nThreads;
    int rc = finn_engine_load(g_engine, &cfg);

    if (!g_storage) {
        g_storage = finn_storage_create();
        finn_storage_open(g_storage, ":memory:");
    }
    if (!g_dispatcher) {
        g_dispatcher = finn_agent_dispatcher_create("/data/storage/el2/base/haps/entry/files", 0, 0, nullptr);
    }

    napi_value ret;
    napi_create_int32(env, rc, &ret);
    return ret;
}

static napi_value ChatStream(napi_env env, napi_callback_info info) {
    size_t argc = 2;
    napi_value args[2];
    napi_get_cb_info(env, info, &argc, args, nullptr, nullptr);

    char buf[8192] = {0};
    size_t len = 0;
    napi_get_value_string_utf8(env, args[0], buf, sizeof(buf), &len);

    StreamCtx ctx{env, args[1]};
    std::string out;
    finn_token_callback_fn cb = stream_trampoline;
    finn_engine_stats_t st{};
    int rc = finn_engine_generate_text(g_engine, buf, 0.7f, 256, cb, &ctx, &st);

    napi_value ret;
    napi_create_int32(env, rc, &ret);
    return ret;
}

static napi_value SynthesizeSpeech(napi_env env, napi_callback_info info) {
    size_t argc = 2;
    napi_value args[2];
    napi_get_cb_info(env, info, &argc, args, nullptr, nullptr);

    char text[4096] = {0};
    char voice[256] = {0};
    size_t l1 = 0, l2 = 0;
    napi_get_value_string_utf8(env, args[0], text, sizeof(text), &l1);
    napi_get_value_string_utf8(env, args[1], voice, sizeof(voice), &l2);

    std::vector<int16_t> pcm;
    finn_audio_callback_fn cb = [](const int16_t* samples, size_t count, int /*sr*/, void* ud) -> int {
        auto* v = static_cast<std::vector<int16_t>*>(ud);
        v->insert(v->end(), samples, samples + count);
        return 0;
    };
    finn_engine_speak(g_engine, text, voice[0] ? voice : "default", cb, &pcm);

    void* data = nullptr;
    napi_value ab;
    napi_create_arraybuffer(env, pcm.size() * sizeof(int16_t), &data, &ab);
    if (data && !pcm.empty()) {
        std::memcpy(data, pcm.data(), pcm.size() * sizeof(int16_t));
    }
    return ab;
}

static napi_value NotesSearch(napi_env env, napi_callback_info info) {
    size_t argc = 1;
    napi_value args[1];
    napi_get_cb_info(env, info, &argc, args, nullptr, nullptr);

    char q[1024] = {0};
    size_t l = 0;
    napi_get_value_string_utf8(env, args[0], q, sizeof(q), &l);

    char* json = g_storage ? finn_storage_search_notes_json(g_storage, q) : nullptr;
    napi_value ret;
    if (json) {
        napi_create_string_utf8(env, json, NAPI_AUTO_LENGTH, &ret);
        finn_free_string(json);
    } else {
        napi_create_string_utf8(env, "[]", NAPI_AUTO_LENGTH, &ret);
    }
    return ret;
}

static napi_value MiniAppsList(napi_env env, napi_callback_info info) {
    napi_value ret;
    char* json = g_storage ? finn_storage_list_mini_apps_json(g_storage) : nullptr;
    if (json) {
        napi_create_string_utf8(env, json, NAPI_AUTO_LENGTH, &ret);
        finn_free_string(json);
    } else {
        napi_create_string_utf8(env, "[]", NAPI_AUTO_LENGTH, &ret);
    }
    return ret;
}

static napi_value RunSandboxTool(napi_env env, napi_callback_info info) {
    size_t argc = 2;
    napi_value args[2];
    napi_get_cb_info(env, info, &argc, args, nullptr, nullptr);

    char name[256] = {0};
    char argsJson[4096] = {0};
    size_t l1 = 0, l2 = 0;
    napi_get_value_string_utf8(env, args[0], name, sizeof(name), &l1);
    napi_get_value_string_utf8(env, args[1], argsJson, sizeof(argsJson), &l2);

    char* res = nullptr;
    if (g_dispatcher) {
        res = finn_agent_dispatch_tool(g_dispatcher, name, argsJson);
    }
    napi_value ret;
    if (res) {
        napi_create_string_utf8(env, res, NAPI_AUTO_LENGTH, &ret);
        finn_free_string(res);
    } else {
        napi_create_string_utf8(env, "{}", NAPI_AUTO_LENGTH, &ret);
    }
    return ret;
}

static napi_value DestroyEngine(napi_env env, napi_callback_info info) {
    if (g_engine) { finn_engine_destroy(g_engine); g_engine = nullptr; }
    if (g_storage) { finn_storage_close(g_storage); finn_storage_destroy(g_storage); g_storage = nullptr; }
    if (g_dispatcher) { finn_agent_dispatcher_destroy(g_dispatcher); g_dispatcher = nullptr; }
    napi_value ret;
    napi_create_int32(env, 0, &ret);
    return ret;
}

static napi_value Init(napi_env env, napi_value exports) {
    napi_property_descriptor desc[] = {
        {"initEngine", nullptr, InitEngine, nullptr, nullptr, nullptr, napi_default, nullptr},
        {"chatStream", nullptr, ChatStream, nullptr, nullptr, nullptr, napi_default, nullptr},
        {"synthesizeSpeech", nullptr, SynthesizeSpeech, nullptr, nullptr, nullptr, napi_default, nullptr},
        {"notesSearch", nullptr, NotesSearch, nullptr, nullptr, nullptr, napi_default, nullptr},
        {"miniAppsList", nullptr, MiniAppsList, nullptr, nullptr, nullptr, napi_default, nullptr},
        {"runSandboxTool", nullptr, RunSandboxTool, nullptr, nullptr, nullptr, napi_default, nullptr},
        {"destroyEngine", nullptr, DestroyEngine, nullptr, nullptr, nullptr, napi_default, nullptr},
    };
    napi_define_properties(env, exports, sizeof(desc) / sizeof(desc[0]), desc);
    return exports;
}

NAPI_MODULE(finn_napi, Init)
