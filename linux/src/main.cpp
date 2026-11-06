#include "finn/finn_c.h"
#include "finn/finn_c_api.h"
#include "linux_sandbox.h"
#include "desktop_assistant.h"

#include <iostream>
#include <string>
#include <vector>

#ifdef FINN_HAVE_GTK4
int finn_linux_gtk_main(int argc, char** argv);
#endif

int main(int argc, char** argv) {
#ifdef FINN_HAVE_GTK4
    return finn_linux_gtk_main(argc, argv);
#else
    // Headless smoke-test mode: exercise finn_core + sandbox + desktop assistant.
    finn_engine_t* engine = finn_engine_create_fake();
    finn_engine_config_t cfg{};
    cfg.model_path = "mock.gguf";
    cfg.n_ctx = 1024;
    cfg.n_threads = 2;
    if (finn_engine_load(engine, &cfg) != 0) {
        std::cerr << "engine load failed\n";
        return 1;
    }
    std::string out;
    finn_token_callback_fn cb = [](const char* t, void* u) -> int {
        *static_cast<std::string*>(u) += t;
        return 0;
    };
    finn_engine_stats_t st{};
    finn_engine_generate_text(engine, "Hello Finn", 0.7f, 16, cb, &out, &st);
    std::cout << "engine output: " << out << "\n";

    finn_storage_t* storage = finn_storage_create();
    finn_storage_open(storage, ":memory:");
    finn_storage_create_conversation(storage, "c1", "Smoke Chat");
    finn_storage_add_message(storage, "c1", "user", "hi");
    char* j = finn_storage_list_conversations_json(storage);
    std::cout << "conversations: " << (j ? j : "(null)") << "\n";
    if (j) finn_free_string(j);

    LinuxSandboxResult r = LinuxSandbox::run({"echo", "sandbox-ok"}, ".", 5000, false);
    std::cout << "sandbox exit=" << r.exit_code << " out=" << r.stdout_text;

    DesktopAssistant da;
    da.start();
    da.stop();

    finn_storage_close(storage);
    finn_storage_destroy(storage);
    finn_engine_destroy(engine);
    std::cout << "Finn Linux headless smoke test: OK\n";
    return 0;
#endif
}
