#include "finn/finn_c_api.h"
#include "finn/agent_tools.h"
#include <nlohmann/json.hpp>

#include <cstring>
#include <cstdlib>
#include <memory>
#include <string>
#include <vector>

struct finn_agent_dispatcher_t {
    std::unique_ptr<finn::AgentToolDispatcher> dispatcher;
};

finn_agent_dispatcher_t* finn_agent_dispatcher_create(const char* workspace_dir, int network_allowed, int use_proot, const char* rootfs_path) {
    auto handle = new finn_agent_dispatcher_t();
    finn::SandboxConfig cfg;
    cfg.workspace_dir = workspace_dir ? workspace_dir : ".";
    cfg.permissions.network_allowed = network_allowed != 0;
    cfg.use_proot = use_proot != 0;
    cfg.rootfs_path = rootfs_path ? rootfs_path : "";
    handle->dispatcher = std::make_unique<finn::AgentToolDispatcher>(cfg);
    return handle;
}

void finn_agent_dispatcher_destroy(finn_agent_dispatcher_t* d) {
    if (d) delete d;
}

void finn_agent_dispatcher_set_permissions(finn_agent_dispatcher_t* d, int network_allowed, int process_spawn_allowed, int file_write_allowed, int timeout_ms, size_t max_output_bytes) {
    if (!d || !d->dispatcher) return;
    finn::SandboxConfig cfg = d->dispatcher->config();
    cfg.permissions.network_allowed = network_allowed != 0;
    cfg.permissions.process_spawn_allowed = process_spawn_allowed != 0;
    cfg.permissions.file_write_allowed = file_write_allowed != 0;
    if (timeout_ms > 0) cfg.permissions.timeout_ms = timeout_ms;
    if (max_output_bytes > 0) cfg.permissions.max_output_bytes = max_output_bytes;
    d->dispatcher->set_config(cfg);
}

char* finn_agent_dispatch_json(finn_agent_dispatcher_t* d, const char* tool_call_json) {
    if (!d || !d->dispatcher || !tool_call_json) return nullptr;
    finn::ToolCall call = finn::AgentToolDispatcher::parse_tool_call_json(tool_call_json);
    finn::ToolResult res = d->dispatcher->dispatch(call);
    std::string json = res.to_json();
    char* copy = static_cast<char*>(std::malloc(json.size() + 1));
    if (copy) std::memcpy(copy, json.c_str(), json.size() + 1);
    return copy;
}

char* finn_agent_dispatch_tool(finn_agent_dispatcher_t* d, const char* name, const char* args_json) {
    if (!d || !d->dispatcher || !name) return nullptr;
    std::map<std::string, std::string> args;
    if (args_json) {
        try {
            auto j = nlohmann::json::parse(args_json);
            if (j.is_object()) {
                for (auto it = j.begin(); it != j.end(); ++it) {
                    if (it.value().is_string()) args[it.key()] = it.value().get<std::string>();
                    else args[it.key()] = it.value().dump();
                }
            }
        } catch (...) {}
    }
    finn::ToolResult res = d->dispatcher->execute(name, args);
    std::string json = res.to_json();
    char* copy = static_cast<char*>(std::malloc(json.size() + 1));
    if (copy) std::memcpy(copy, json.c_str(), json.size() + 1);
    return copy;
}

char* finn_agent_available_tools_json(finn_agent_dispatcher_t* d) {
    if (!d || !d->dispatcher) return nullptr;
    auto tools = d->dispatcher->available_tools();
    nlohmann::json j = tools;
    std::string s = j.dump();
    char* copy = static_cast<char*>(std::malloc(s.size() + 1));
    if (copy) std::memcpy(copy, s.c_str(), s.size() + 1);
    return copy;
}

char* finn_agent_audit_log_json(finn_agent_dispatcher_t* d) {
    if (!d || !d->dispatcher) return nullptr;
    auto log = d->dispatcher->audit_log();
    nlohmann::json j = log;
    std::string s = j.dump();
    char* copy = static_cast<char*>(std::malloc(s.size() + 1));
    if (copy) std::memcpy(copy, s.c_str(), s.size() + 1);
    return copy;
}

void finn_agent_clear_audit_log(finn_agent_dispatcher_t* d) {
    if (d && d->dispatcher) d->dispatcher->clear_audit_log();
}

int finn_agent_has_rootfs(finn_agent_dispatcher_t* d) {
    if (!d || !d->dispatcher) return 0;
    return d->dispatcher->has_rootfs() ? 1 : 0;
}

int finn_agent_proot_available(finn_agent_dispatcher_t* d) {
    if (!d || !d->dispatcher) return 0;
    return d->dispatcher->proot_available() ? 1 : 0;
}