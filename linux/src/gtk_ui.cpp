#ifdef FINN_HAVE_GTK4

#include <gtk/gtk.h>
#include <adwaita.h>

#include "finn/finn_c.h"
#include "linux_sandbox.h"
#include "desktop_assistant.h"

#include <string>
#include <vector>
#include <cstdlib>

static finn_engine_t* g_engine = nullptr;
static finn_storage_t* g_storage = nullptr;
static DesktopAssistant g_assistant;

static void append_text(GtkTextBuffer* buf, const char* text) {
    GtkTextIter end;
    gtk_text_buffer_get_end_iter(buf, &end);
    gtk_text_buffer_insert(buf, &end, text, -1);
    gtk_text_buffer_insert(buf, &end, "\n", -1);
}

// ---------- Chat view ----------
static void on_send_clicked(GtkButton* /*btn*/, gpointer user_data) {
    GtkWidget* entry = GTK_WIDGET(user_data);
    const char* txt = gtk_editable_get_text(GTK_EDITABLE(entry));
    GtkTextBuffer* buf = (GtkTextBuffer*)g_object_get_data(G_OBJECT(entry), "chat-buffer");
    if (!txt || !*txt) return;
    std::string line = std::string("You: ") + txt;
    append_text(buf, line.c_str());

    // Echo via fake engine streaming
    finn_engine_stats_t stats{};
    std::string out;
    finn_token_callback_fn cb = [](const char* token, void* ud) -> int {
        auto* s = static_cast<std::string*>(ud);
        *s += token;
        return 0;
    };
    finn_engine_generate_text(g_engine, txt, 0.7f, 128, cb, &out, &stats);
    append_text(buf, (std::string("Finn: ") + out).c_str());
    gtk_editable_set_text(GTK_EDITABLE(entry), "");
}

static GtkWidget* build_chat_page() {
    GtkWidget* box = gtk_box_new(GTK_ORIENTATION_VERTICAL, 8);
    GtkWidget* scroll = gtk_scrolled_window_new();
    gtk_widget_set_vexpand(scroll, TRUE);
    GtkWidget* view = gtk_text_view_new();
    gtk_text_view_set_editable(GTK_TEXT_VIEW(view), FALSE);
    gtk_text_view_set_wrap_mode(GTK_TEXT_VIEW(view), GTK_WRAP_WORD_CHAR);
    GtkTextBuffer* buf = gtk_text_view_get_buffer(GTK_TEXT_VIEW(view));
    gtk_scrolled_window_set_child(GTK_SCROLLED_WINDOW(scroll), view);

    GtkWidget* row = gtk_box_new(GTK_ORIENTATION_HORIZONTAL, 8);
    GtkWidget* entry = gtk_entry_new();
    gtk_widget_set_hexpand(entry, TRUE);
    gtk_entry_set_placeholder_text(GTK_ENTRY(entry), "Message Finn...");
    GtkWidget* send = gtk_button_new_with_label("Send");
    g_signal_connect(send, "clicked", G_CALLBACK(on_send_clicked), entry);

    gtk_box_append(GTK_BOX(row), entry);
    gtk_box_append(GTK_BOX(row), send);

    gtk_box_append(GTK_BOX(box), scroll);
    gtk_box_append(GTK_BOX(box), row);

    // store buffer on the box's root later; store via entry's root at click time instead.
    // We stash buffer on the entry object for retrieval.
    g_object_set_data(G_OBJECT(entry), "chat-buffer", buf);
    // Also stash on window when realized: use entry data lookup in handler via root; simpler: store globally.
    static GtkTextBuffer* g_chat_buf = nullptr;
    g_chat_buf = buf;
    // Override append to use global
    (void)g_chat_buf;
    return box;
}

// ---------- Live view ----------
static finn_live_session_t* g_live = nullptr;
static void on_live_start(GtkButton* /*b*/, gpointer /*d*/) {
    if (g_live) return;
    finn_live_config_t cfg{};
    cfg.vad_energy_threshold = 0.02f;
    cfg.vad_silence_timeout_ms = 800;
    cfg.voice = "default";
    cfg.system_prompt = "You are Finn, a helpful on-device assistant.";
    g_live = finn_live_create(g_engine, &cfg);
    finn_live_start(g_live);
}
static void on_live_stop(GtkButton* /*b*/, gpointer /*d*/) {
    if (!g_live) return;
    finn_live_stop(g_live);
    finn_live_destroy(g_live);
    g_live = nullptr;
}

static GtkWidget* build_live_page() {
    GtkWidget* box = gtk_box_new(GTK_ORIENTATION_VERTICAL, 12);
    gtk_widget_set_margin_top(box, 24);
    GtkWidget* title = gtk_label_new("Live voice + camera mode");
    gtk_widget_add_css_class(title, "title-1");
    GtkWidget* status = gtk_label_new("Idle");
    GtkWidget* row = gtk_box_new(GTK_ORIENTATION_HORIZONTAL, 8);
    GtkWidget* start = gtk_button_new_with_label("Start");
    GtkWidget* stop = gtk_button_new_with_label("Stop");
    g_signal_connect(start, "clicked", G_CALLBACK(on_live_start), nullptr);
    g_signal_connect(stop, "clicked", G_CALLBACK(on_live_stop), nullptr);
    gtk_box_append(GTK_BOX(row), start);
    gtk_box_append(GTK_BOX(row), stop);
    gtk_box_append(GTK_BOX(box), title);
    gtk_box_append(GTK_BOX(box), status);
    gtk_box_append(GTK_BOX(box), row);
    // Camera preview placeholder
    GtkWidget* cam = gtk_picture_new();
    gtk_widget_set_size_request(cam, 320, 240);
    gtk_box_append(GTK_BOX(box), cam);
    return box;
}

// ---------- Notes view (FTS5) ----------
static GtkWidget* g_notes_list = nullptr;
static void refresh_notes(const char* query) {
    if (!g_notes_list) return;
    // clear
    GtkWidget* child = gtk_widget_get_first_child(g_notes_list);
    while (child) {
        GtkWidget* next = gtk_widget_get_next_sibling(child);
        gtk_list_box_remove(GTK_LIST_BOX(g_notes_list), child);
        child = next;
    }
    char* json = query && *query
        ? finn_storage_search_notes_json(g_storage, query)
        : finn_storage_list_notes_json(g_storage, nullptr, nullptr);
    if (!json) return;
    // Minimal: show raw JSON in a single row (full parsing would use json-glib).
    GtkWidget* row = gtk_label_new(json);
    gtk_label_set_wrap(GTK_LABEL(row), TRUE);
    gtk_list_box_append(GTK_LIST_BOX(g_notes_list), row);
    finn_free_string(json);
}

static void on_notes_search_changed(GtkEditable* e, gpointer /*d*/) {
    refresh_notes(gtk_editable_get_text(e));
}

static GtkWidget* build_notes_page() {
    GtkWidget* box = gtk_box_new(GTK_ORIENTATION_VERTICAL, 8);
    GtkWidget* search = gtk_search_entry_new();
    g_signal_connect(search, "search-changed", G_CALLBACK(on_notes_search_changed), nullptr);
    g_notes_list = gtk_list_box_new();
    GtkWidget* scroll = gtk_scrolled_window_new();
    gtk_scrolled_window_set_child(GTK_SCROLLED_WINDOW(scroll), g_notes_list);
    gtk_widget_set_vexpand(scroll, TRUE);
    gtk_box_append(GTK_BOX(box), search);
    gtk_box_append(GTK_BOX(box), scroll);
    refresh_notes("");
    return box;
}

// ---------- MiniApp view (WebKitGTK sandbox) ----------
static GtkWidget* build_miniapp_page() {
    GtkWidget* box = gtk_box_new(GTK_ORIENTATION_VERTICAL, 8);
#ifdef FINN_HAVE_WEBKIT
    // WebKitWebView would be created here; requires webkit2gtk headers.
    GtkWidget* placeholder = gtk_label_new("WebKitGTK MiniApp sandbox (enable FINN_HAVE_WEBKIT)");
#else
    GtkWidget* placeholder = gtk_label_new("MiniApp WebKit view unavailable (WebKitGTK not found)");
#endif
    gtk_box_append(GTK_BOX(box), placeholder);

    // Demonstrate bubblewrap runner
    GtkWidget* runbtn = gtk_button_new_with_label("Run sandboxed tool (bwrap)");
    g_signal_connect(runbtn, "clicked", G_CALLBACK(+[](GtkButton*, gpointer) {
        LinuxSandboxResult r = LinuxSandbox::run({"echo", "hello from sandbox"}, "/tmp", 5000, false);
        g_print("sandbox exit=%d out=%s\n", r.exit_code, r.stdout_text.c_str());
    }), nullptr);
    gtk_box_append(GTK_BOX(box), runbtn);
    return box;
}

static void activate(GtkApplication* app, gpointer /*user_data*/) {
    if (!g_engine) {
        g_engine = finn_engine_create_fake();
        finn_engine_config_t cfg{};
        cfg.model_path = "mock.gguf";
        cfg.n_ctx = 2048;
        cfg.n_threads = 4;
        finn_engine_load(g_engine, &cfg);
    }
    if (!g_storage) {
        g_storage = finn_storage_create();
        finn_storage_open(g_storage, ":memory:");
    }
    g_assistant.start();

    AdwApplicationWindow* win = ADW_APPLICATION_WINDOW(adw_application_window_new(GTK_APPLICATION(app)));
    adw_application_window_set_title(win, "Finn DeepSystem");
    adw_application_window_set_default_size(win, 900, 640);

    AdwToolbarView* toolbar = ADW_TOOLBAR_VIEW(adw_toolbar_view_new());
    AdwHeaderBar* header = ADW_HEADER_BAR(adw_header_bar_new());
    adw_toolbar_view_add_top_bar(toolbar, GTK_WIDGET(header));

    GtkNotebook* nb = GTK_NOTEBOOK(gtk_notebook_new());
    gtk_notebook_append_page(nb, build_chat_page(), gtk_label_new("Chat"));
    gtk_notebook_append_page(nb, build_live_page(), gtk_label_new("Live"));
    gtk_notebook_append_page(nb, build_notes_page(), gtk_label_new("Notes"));
    gtk_notebook_append_page(nb, build_miniapp_page(), gtk_label_new("MiniApps"));
    adw_toolbar_view_set_content(toolbar, GTK_WIDGET(nb));

    adw_application_window_set_content(win, GTK_WIDGET(toolbar));
    gtk_window_present(GTK_WINDOW(win));
}

int finn_linux_gtk_main(int argc, char** argv) {
    adw_init();
    GtkApplication* app = gtk_application_new("ai.finn.deepsystem", G_APPLICATION_DEFAULT_FLAGS);
    g_signal_connect(app, "activate", G_CALLBACK(activate), nullptr);
    int status = g_application_run(G_APPLICATION(app), argc, argv);
    g_object_unref(app);
    if (g_live) { finn_live_stop(g_live); finn_live_destroy(g_live); g_live = nullptr; }
    if (g_engine) { finn_engine_destroy(g_engine); g_engine = nullptr; }
    if (g_storage) { finn_storage_close(g_storage); finn_storage_destroy(g_storage); g_storage = nullptr; }
    g_assistant.stop();
    return status;
}

#endif // FINN_HAVE_GTK4
