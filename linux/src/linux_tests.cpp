#include "finn/finn_c.h"
#include "linux_sandbox.h"
#include "desktop_assistant.h"

#include <cassert>
#include <iostream>
#include <string>

int main() {
    // Engine lifecycle
    finn_engine_t* engine = finn_engine_create_fake();
    assert(engine != nullptr);
    finn_engine_config_t cfg{};
    cfg.model_path = "mock.gguf";
    cfg.n_ctx = 512;
    cfg.n_threads = 2;
    assert(finn_engine_load(engine, &cfg) == 0);
    assert(finn_engine_is_loaded(engine) == 1);

    std::string out;
    finn_token_callback_fn cb = [](const char* t, void* u) -> int {
        *static_cast<std::string*>(u) += t;
        return 0;
    };
    finn_engine_stats_t st{};
    int rc = finn_engine_generate_text(engine, "Hello", 0.7f, 16, cb, &out, &st);
    assert(rc == 0);
    assert(!out.empty());
    finn_engine_destroy(engine);

    // Storage
    finn_storage_t* storage = finn_storage_create();
    assert(storage != nullptr);
    assert(finn_storage_open(storage, ":memory:") == 0);
    assert(finn_storage_create_conversation(storage, "c1", "Test Conv") == 0);
    assert(finn_storage_add_message(storage, "c1", "user", "hi") == 0);
    char* j = finn_storage_list_conversations_json(storage);
    assert(j != nullptr);
    assert(std::string(j).find("Test Conv") != std::string::npos);
    finn_free_string(j);
    finn_storage_close(storage);
    finn_storage_destroy(storage);

    // Sandbox
    LinuxSandboxResult r = LinuxSandbox::run({"echo", "hello"}, ".", 5000, false);
    assert(r.exit_code == 0);
    assert(r.stdout_text.find("hello") != std::string::npos);

    // Desktop assistant
    DesktopAssistant da;
    da.start();
    assert(da.isRunning());
    da.stop();
    assert(!da.isRunning());

    std::cout << "linux_tests: PASS\n";
    return 0;
}
