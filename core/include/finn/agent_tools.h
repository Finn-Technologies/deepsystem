#pragma once

#include <cstdint>
#include <string>
#include <vector>
#include <map>
#include <functional>
#include <chrono>

namespace finn {

struct SandboxPermissions {
    bool network_allowed = false;
    bool file_write_allowed = true;
    bool process_spawn_allowed = true;
    std::vector<std::string> allowed_commands; // empty => any
    size_t max_output_bytes = 64 * 1024;
    int timeout_ms = 30000;
    std::string workspace_subdir; // optional restricted subdir
};

struct SandboxConfig {
    std::string workspace_dir;
    std::string rootfs_path; // optional proot rootfs
    bool use_proot = false;
    SandboxPermissions permissions;
    std::string audit_log_path; // optional file
    bool honest_isolation = true; // proot is rootless, not VM
};

struct ToolCall {
    std::string id;
    std::string name;
    std::map<std::string, std::string> args;

    std::string get(const std::string& key, const std::string& def = "") const {
        auto it = args.find(key);
        if (it == args.end()) return def;
        return it->second;
    }
};

struct ToolResult {
    std::string tool_call_id;
    std::string name;
    bool success = false;
    int exit_code = 0;
    bool timed_out = false;
    std::string stdout_text;
    std::string stderr_text;
    std::string error;
    int64_t duration_ms = 0;
    bool truncated = false;
    std::string audit_entry;

    std::string to_json() const;
};

class AgentToolDispatcher {
public:
    AgentToolDispatcher();
    explicit AgentToolDispatcher(const SandboxConfig& cfg);

    void set_config(const SandboxConfig& cfg);
    const SandboxConfig& config() const { return cfg_; }

    std::vector<std::string> available_tools() const;

    static ToolCall parse_tool_call_json(const std::string& json);
    static std::vector<ToolCall> parse_tool_calls_json(const std::string& json);

    ToolResult dispatch(const ToolCall& call);
    ToolResult execute(const std::string& name, const std::map<std::string, std::string>& args);

    std::vector<std::string> audit_log() const { return audit_; }
    void clear_audit_log() { audit_.clear(); }

    // Backend helpers exposed for tests
    bool proot_available() const;
    bool has_rootfs() const;

private:
    SandboxConfig cfg_;
    std::vector<std::string> audit_;

    ToolResult run_bash(const ToolCall& call, bool python);
    ToolResult file_read(const ToolCall& call);
    ToolResult file_write(const ToolCall& call);
    ToolResult web_fetch(const ToolCall& call);

    struct ExecResult {
        int exit_code = -1;
        std::string stdout_text;
        std::string stderr_text;
        bool timed_out = false;
        bool truncated = false;
    };
    ExecResult run_subprocess(const std::vector<std::string>& argv,
                              const std::string& cwd,
                              int timeout_ms,
                              size_t max_output_bytes,
                              bool network_allowed);

    bool command_allowed(const std::string& cmd) const;
    std::string resolve_path(const std::string& p, bool must_exist = false) const;
    bool path_in_workspace(const std::string& resolved) const;
    bool network_permitted() const;

    void audit(const ToolCall& call, const ToolResult& res);

    static std::string find_binary(const std::string& name);
};

} // namespace finn