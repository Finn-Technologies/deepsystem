#include "finn/agent_tools.h"

#include <nlohmann/json.hpp>

#include <algorithm>
#include <chrono>
#include <cstdlib>
#include <cstring>
#include <filesystem>
#include <fstream>
#include <iostream>
#include <sstream>
#include <sys/stat.h>
#include <sys/types.h>
#include <sys/wait.h>
#include <unistd.h>
#include <fcntl.h>
#include <poll.h>
#include <signal.h>
#include <dirent.h>

namespace fs = std::filesystem;
using json = nlohmann::json;

namespace finn {

static int64_t now_ms() {
    using namespace std::chrono;
    return duration_cast<milliseconds>(steady_clock::now().time_since_epoch()).count();
}

static std::string trim(const std::string& s) {
    size_t a = s.find_first_not_of(" \t\r\n");
    if (a == std::string::npos) return "";
    size_t b = s.find_last_not_of(" \t\r\n");
    return s.substr(a, b - a + 1);
}

static std::string shell_quote(const std::string& s) {
    // Not used for exec (argv), kept for audit
    return s;
}

AgentToolDispatcher::AgentToolDispatcher() {
    cfg_.workspace_dir = ".";
    if (cfg_.workspace_dir.empty()) cfg_.workspace_dir = ".";
}

AgentToolDispatcher::AgentToolDispatcher(const SandboxConfig& cfg) : cfg_(cfg) {
    if (cfg_.workspace_dir.empty()) cfg_.workspace_dir = ".";
}

void AgentToolDispatcher::set_config(const SandboxConfig& cfg) {
    cfg_ = cfg;
    if (cfg_.workspace_dir.empty()) cfg_.workspace_dir = ".";
}

std::vector<std::string> AgentToolDispatcher::available_tools() const {
    return {"bash_exec", "python_run", "file_read", "file_write", "web_fetch"};
}

std::string AgentToolDispatcher::find_binary(const std::string& name) {
    const char* path_env = std::getenv("PATH");
    std::string paths = path_env ? path_env : "/usr/bin:/bin:/usr/local/bin";
    std::istringstream iss(paths);
    std::string dir;
    while (std::getline(iss, dir, ':')) {
        if (dir.empty()) continue;
        std::string candidate = dir + "/" + name;
        struct stat st;
        if (stat(candidate.c_str(), &st) == 0 && (st.st_mode & S_IXUSR)) {
            return candidate;
        }
    }
    return "";
}

bool AgentToolDispatcher::proot_available() const {
    return !find_binary("proot").empty();
}

bool AgentToolDispatcher::has_rootfs() const {
    if (cfg_.rootfs_path.empty()) return false;
    struct stat st;
    return stat(cfg_.rootfs_path.c_str(), &st) == 0 && S_ISDIR(st.st_mode);
}

bool AgentToolDispatcher::network_permitted() const {
    return cfg_.permissions.network_allowed;
}

bool AgentToolDispatcher::command_allowed(const std::string& cmd) const {
    if (cfg_.permissions.allowed_commands.empty()) return true;
    // first token
    std::istringstream iss(cmd);
    std::string first;
    iss >> first;
    // strip path
    size_t slash = first.find_last_of("/\\");
    if (slash != std::string::npos) first = first.substr(slash + 1);
    for (const auto& c : cfg_.permissions.allowed_commands) {
        if (c == first) return true;
    }
    return false;
}

std::string AgentToolDispatcher::resolve_path(const std::string& p, bool /*must_exist*/) const {
    fs::path base = fs::path(cfg_.workspace_dir);
    fs::path input = p;
    fs::path resolved;
    if (input.is_absolute()) {
        resolved = input;
    } else {
        resolved = base / input;
    }
    // normalize lexical
    std::vector<std::string> parts;
    for (const auto& comp : resolved) {
        std::string s = comp.string();
        if (s.empty() || s == ".") continue;
        if (s == "..") {
            if (!parts.empty()) parts.pop_back();
        } else {
            parts.push_back(s);
        }
    }
    // rebuild
    std::string out;
    bool absolute = resolved.is_absolute();
    if (absolute) out = "/";
    for (size_t i = 0; i < parts.size(); ++i) {
        if (i) out += "/";
        out += parts[i];
    }
    if (out.empty()) out = cfg_.workspace_dir;
    return out;
}

bool AgentToolDispatcher::path_in_workspace(const std::string& resolved) const {
    std::error_code ec;
    fs::path base_c = fs::weakly_canonical(cfg_.workspace_dir, ec);
    if (ec) base_c = fs::path(cfg_.workspace_dir).lexically_normal();
    std::string b = base_c.string();
    if (b.empty()) b = ".";
    if (b.back() != '/') b += "/";

    // canonicalize resolved if possible
    std::string r = resolved;
    if (fs::exists(resolved, ec)) {
        auto rc = fs::weakly_canonical(resolved, ec);
        if (!ec) r = rc.string();
    } else {
        fs::path p(resolved);
        auto parent = p.parent_path();
        if (fs::exists(parent, ec)) {
            auto pc = fs::weakly_canonical(parent, ec);
            if (!ec) r = (pc / p.filename()).string();
        }
    }
    if (r.rfind(b, 0) == 0) return true;
    // lexical fallback
    std::string rl = fs::path(resolved).lexically_normal().string();
    std::string bl = fs::path(cfg_.workspace_dir).lexically_normal().string();
    if (bl.back() != '/') bl += "/";
    if (rl.rfind(bl, 0) == 0) return true;
    return false;
}

// subprocess

AgentToolDispatcher::ExecResult AgentToolDispatcher::run_subprocess(
    const std::vector<std::string>& argv,
    const std::string& cwd,
    int timeout_ms,
    size_t max_output_bytes,
    bool network_allowed) {

    ExecResult result;
    if (argv.empty()) { result.stderr_text = "empty argv"; return result; }

    int out_pipe[2], err_pipe[2];
    if (pipe(out_pipe) != 0 || pipe(err_pipe) != 0) {
        result.stderr_text = "pipe failed";
        return result;
    }

    pid_t pid = fork();
    if (pid < 0) {
        result.stderr_text = "fork failed";
        return result;
    }

    if (pid == 0) {
        // child
        // new session/process group
        setpgid(0, 0);
        chdir(cwd.c_str());
        // redirect stdout/stderr
        dup2(out_pipe[1], STDOUT_FILENO);
        dup2(err_pipe[1], STDERR_FILENO);
        close(out_pipe[0]); close(out_pipe[1]);
        close(err_pipe[0]); close(err_pipe[1]);

        // clean env
        std::vector<std::string> env_strings;
        env_strings.push_back("PATH=/usr/bin:/bin:/usr/local/bin:/opt/homebrew/bin");
        env_strings.push_back("HOME=" + cwd);
        env_strings.push_back("TMPDIR=" + cwd + "/tmp");
        env_strings.push_back("LANG=C.UTF-8");
        std::vector<char*> envp;
        for (auto& e : env_strings) envp.push_back(const_cast<char*>(e.c_str()));
        envp.push_back(nullptr);

        // build argv
        std::vector<char*> args;
        for (const auto& a : argv) args.push_back(const_cast<char*>(a.c_str()));
        args.push_back(nullptr);

        std::string exe = argv[0];
        if (exe.find('/') == std::string::npos) {
            std::string found = find_binary(exe);
            if (!found.empty()) exe = found;
        }
        execve(exe.c_str(), args.data(), envp.data());
        // if exec fails
        fprintf(stderr, "exec failed: %s\n", strerror(errno));
        _exit(127);
    }

    close(out_pipe[1]);
    close(err_pipe[1]);

    // set non-blocking
    fcntl(out_pipe[0], F_SETFL, O_NONBLOCK);
    fcntl(err_pipe[0], F_SETFL, O_NONBLOCK);

    auto start = std::chrono::steady_clock::now();
    bool timed_out = false;
    std::string out_buf, err_buf;
    size_t out_total = 0, err_total = 0;

    while (true) {
        // check timeout
        auto elapsed = std::chrono::duration_cast<std::chrono::milliseconds>(std::chrono::steady_clock::now() - start).count();
        if (elapsed >= timeout_ms) {
            timed_out = true;
            kill(-pid, SIGKILL);
            break;
        }

        struct pollfd pfds[2];
        pfds[0].fd = out_pipe[0];
        pfds[0].events = POLLIN;
        pfds[1].fd = err_pipe[0];
        pfds[1].events = POLLIN;

        int pret = poll(pfds, 2, 100);
        bool progress = false;
        if (pret > 0) {
            for (int i = 0; i < 2; ++i) {
                if (pfds[i].revents & POLLIN) {
                    char buf[4096];
                    ssize_t n = read(pfds[i].fd, buf, sizeof(buf));
                    if (n > 0) {
                        progress = true;
                        std::string* target = (i == 0) ? &out_buf : &err_buf;
                        size_t* tot = (i == 0) ? &out_total : &err_total;
                        if (*tot < max_output_bytes) {
                            size_t take = std::min((size_t)n, max_output_bytes - *tot);
                            target->append(buf, take);
                            *tot += take;
                            if (take < (size_t)n) result.truncated = true;
                        } else {
                            result.truncated = true;
                        }
                    }
                }
            }
        }

        // check if child exited
        int status;
        pid_t w = waitpid(pid, &status, WNOHANG);
        if (w == pid) {
            // drain remaining
            char buf[4096];
            while (true) {
                ssize_t n = read(out_pipe[0], buf, sizeof(buf));
                if (n <= 0) break;
                if (out_total < max_output_bytes) {
                    size_t take = std::min((size_t)n, max_output_bytes - out_total);
                    out_buf.append(buf, take);
                    out_total += take;
                    if (take < (size_t)n) result.truncated = true;
                } else result.truncated = true;
            }
            while (true) {
                ssize_t n = read(err_pipe[0], buf, sizeof(buf));
                if (n <= 0) break;
                if (err_total < max_output_bytes) {
                    size_t take = std::min((size_t)n, max_output_bytes - err_total);
                    err_buf.append(buf, take);
                    err_total += take;
                    if (take < (size_t)n) result.truncated = true;
                } else result.truncated = true;
            }
            if (WIFEXITED(status)) result.exit_code = WEXITSTATUS(status);
            else if (WIFSIGNALED(status)) result.exit_code = 128 + WTERMSIG(status);
            break;
        }
        if (w < 0) break;
    }

    if (timed_out) {
        result.exit_code = 124;
        // wait
        waitpid(pid, nullptr, 0);
    }

    close(out_pipe[0]);
    close(err_pipe[0]);

    result.stdout_text = out_buf;
    result.stderr_text = err_buf;
    result.timed_out = timed_out;
    if (result.truncated) result.truncated = true;
    // mark truncated if total exceeded
    if (out_total >= max_output_bytes || err_total >= max_output_bytes) result.truncated = true;
    return result;
}

ToolResult AgentToolDispatcher::run_bash(const ToolCall& call, bool python) {
    ToolResult res;
    res.tool_call_id = call.id;
    res.name = call.name;

    if (!cfg_.permissions.process_spawn_allowed) {
        res.error = "permission_denied: process_spawn not allowed";
        return res;
    }

    std::string command;
    if (python) {
        std::string code = call.get("code");
        if (code.empty()) code = call.get("script");
        if (code.empty()) code = call.get("command");
        // write to temp file? use python3 -c
        command = "python3 -c " + code; // risk; better pass argv
        // We'll handle separately below by building argv
    } else {
        command = call.get("command");
        if (command.empty()) command = call.get("cmd");
    }

    // command allowlist check on first token (for bash)
    if (!python) {
        if (!command_allowed(command)) {
            res.error = "permission_denied: command not allowed";
            return res;
        }
    }

    int timeout = cfg_.permissions.timeout_ms;
    if (call.args.count("timeout_ms")) {
        try { timeout = std::stoi(call.get("timeout_ms")); } catch (...) {}
    }
    if (timeout <= 0) timeout = cfg_.permissions.timeout_ms;

    // Build argv
    std::vector<std::string> argv;
    bool use_proot = cfg_.use_proot && has_rootfs() && proot_available();
    std::string cwd = cfg_.workspace_dir;
    // ensure workspace exists
    std::error_code ec;
    fs::create_directories(cwd, ec);
    fs::create_directories(cwd + "/tmp", ec);

    if (python) {
        std::string code = call.get("code");
        if (code.empty()) code = call.get("script");
        if (code.empty()) code = call.get("command");
        if (use_proot) {
            std::string proot = find_binary("proot");
            argv = {proot, "-r", cfg_.rootfs_path, "-b", cwd + ":/workspace", "-w", "/workspace",
                    "python3", "-c", code};
            cwd = cfg_.workspace_dir; // proot handles
        } else {
            argv = {"python3", "-c", code};
        }
    } else {
        if (use_proot) {
            std::string proot = find_binary("proot");
            argv = {proot, "-r", cfg_.rootfs_path, "-b", cwd + ":/workspace", "-w", "/workspace",
                    "sh", "-c", command};
        } else {
            argv = {"sh", "-c", command};
        }
    }

    auto t0 = now_ms();
    ExecResult er = run_subprocess(argv, cwd, timeout, cfg_.permissions.max_output_bytes, cfg_.permissions.network_allowed);
    auto t1 = now_ms();

    res.exit_code = er.exit_code;
    res.stdout_text = er.stdout_text;
    res.stderr_text = er.stderr_text;
    res.timed_out = er.timed_out;
    res.truncated = er.truncated;
    res.duration_ms = t1 - t0;
    res.success = (!er.timed_out && er.exit_code == 0);
    if (er.timed_out) res.error = "timeout";
    return res;
}

ToolResult AgentToolDispatcher::file_read(const ToolCall& call) {
    ToolResult res;
    res.tool_call_id = call.id;
    res.name = call.name;

    std::string path = call.get("path");
    if (path.empty()) path = call.get("file");
    if (path.empty()) { res.error = "missing path"; return res; }

    std::string resolved = resolve_path(path);
    if (!path_in_workspace(resolved)) {
        res.error = "permission_denied: path outside workspace";
        return res;
    }

    std::ifstream f(resolved, std::ios::binary);
    if (!f) { res.error = "file not found: " + resolved; return res; }
    std::ostringstream ss;
    ss << f.rdbuf();
    std::string content = ss.str();
    // truncate
    if (content.size() > cfg_.permissions.max_output_bytes) {
        content = content.substr(0, cfg_.permissions.max_output_bytes);
        res.truncated = true;
    }
    res.stdout_text = content;
    res.success = true;
    res.exit_code = 0;
    return res;
}

ToolResult AgentToolDispatcher::file_write(const ToolCall& call) {
    ToolResult res;
    res.tool_call_id = call.id;
    res.name = call.name;

    if (!cfg_.permissions.file_write_allowed) {
        res.error = "permission_denied: file_write not allowed";
        return res;
    }

    std::string path = call.get("path");
    if (path.empty()) path = call.get("file");
    if (path.empty()) { res.error = "missing path"; return res; }
    std::string content = call.get("content");
    if (content.empty() && call.args.count("data")) content = call.get("data");

    std::string resolved = resolve_path(path);
    if (!path_in_workspace(resolved)) {
        res.error = "permission_denied: path outside workspace";
        return res;
    }

    std::error_code ec;
    fs::path p(resolved);
    fs::create_directories(p.parent_path(), ec);
    std::ofstream f(resolved, std::ios::binary | std::ios::trunc);
    if (!f) { res.error = "cannot write: " + resolved; return res; }
    f << content;
    f.close();
    res.stdout_text = "wrote " + std::to_string(content.size()) + " bytes to " + resolved;
    res.success = true;
    res.exit_code = 0;
    return res;
}

ToolResult AgentToolDispatcher::web_fetch(const ToolCall& call) {
    ToolResult res;
    res.tool_call_id = call.id;
    res.name = call.name;

    if (!network_permitted()) {
        res.error = "permission_denied: network not allowed";
        return res;
    }

    std::string url = call.get("url");
    if (url.empty()) { res.error = "missing url"; return res; }

    // audit network via caller? NetworkLedger is Android-side; core just executes.
    // Use curl if available
    std::string curl = find_binary("curl");
    if (curl.empty()) {
        res.error = "curl not available";
        return res;
    }
    std::vector<std::string> argv = {curl, "-sS", "-L", "--max-time", "20", url};
    std::string cwd = cfg_.workspace_dir;
    std::error_code ec;
    fs::create_directories(cwd, ec);
    auto t0 = now_ms();
    ExecResult er = run_subprocess(argv, cwd, 25000, cfg_.permissions.max_output_bytes, true);
    auto t1 = now_ms();
    res.exit_code = er.exit_code;
    res.stdout_text = er.stdout_text;
    res.stderr_text = er.stderr_text;
    res.truncated = er.truncated;
    res.duration_ms = t1 - t0;
    res.success = (er.exit_code == 0);
    if (!res.success && res.error.empty()) res.error = er.stderr_text;
    return res;
}

ToolResult AgentToolDispatcher::dispatch(const ToolCall& call) {
    ToolResult res;
    if (call.name == "bash_exec") {
        res = run_bash(call, false);
    } else if (call.name == "python_run") {
        res = run_bash(call, true);
    } else if (call.name == "file_read") {
        res = file_read(call);
    } else if (call.name == "file_write") {
        res = file_write(call);
    } else if (call.name == "web_fetch") {
        res = web_fetch(call);
    } else {
        res.tool_call_id = call.id;
        res.name = call.name;
        res.error = "unknown tool: " + call.name;
    }
    res.tool_call_id = call.id;
    res.name = call.name;
    audit(call, res);
    return res;
}

ToolResult AgentToolDispatcher::execute(const std::string& name, const std::map<std::string, std::string>& args) {
    ToolCall c;
    c.id = "call_0";
    c.name = name;
    c.args = args;
    return dispatch(c);
}

void AgentToolDispatcher::audit(const ToolCall& call, const ToolResult& res) {
    std::ostringstream ss;
    ss << "{\"tool\":\"" << call.name << "\",\"id\":\"" << call.id << "\",\"success\":" << (res.success ? "true" : "false")
       << ",\"exit_code\":" << res.exit_code << ",\"duration_ms\":" << res.duration_ms;
    if (!res.error.empty()) ss << ",\"error\":\"" << res.error << "\"";
    ss << "}";
    std::string entry = ss.str();
    audit_.push_back(entry);
    // file audit
    if (!cfg_.audit_log_path.empty()) {
        std::ofstream f(cfg_.audit_log_path, std::ios::app);
        if (f) f << entry << "\n";
    }
}

ToolCall AgentToolDispatcher::parse_tool_call_json(const std::string& json_str) {
    ToolCall call;
    try {
        json j = json::parse(json_str);
        // unwrap OpenAI
        if (j.contains("function") && j["function"].is_object()) {
            j = j["function"];
        }
        if (j.contains("tool_call") && j["tool_call"].is_object()) {
            j = j["tool_call"];
        }
        if (j.contains("name")) call.name = j["name"].get<std::string>();
        else if (j.contains("tool")) call.name = j["tool"].get<std::string>();
        else if (j.contains("function_name")) call.name = j["function_name"].get<std::string>();

        if (j.contains("id")) {
            if (j["id"].is_string()) call.id = j["id"].get<std::string>();
            else call.id = j["id"].dump();
        }
        if (call.id.empty()) call.id = "call_0";

        // arguments
        if (j.contains("arguments")) {
            if (j["arguments"].is_string()) {
                try {
                    json a = json::parse(j["arguments"].get<std::string>());
                    if (a.is_object()) {
                        for (auto it = a.begin(); it != a.end(); ++it) {
                            if (it.value().is_string()) call.args[it.key()] = it.value().get<std::string>();
                            else call.args[it.key()] = it.value().dump();
                        }
                    }
                } catch (...) {
                    call.args["command"] = j["arguments"].get<std::string>();
                }
            } else if (j["arguments"].is_object()) {
                for (auto it = j["arguments"].begin(); it != j["arguments"].end(); ++it) {
                    if (it.value().is_string()) call.args[it.key()] = it.value().get<std::string>();
                    else call.args[it.key()] = it.value().dump();
                }
            }
        }
        if (j.contains("args")) {
            if (j["args"].is_object()) {
                for (auto it = j["args"].begin(); it != j["args"].end(); ++it) {
                    if (it.value().is_string()) call.args[it.key()] = it.value().get<std::string>();
                    else call.args[it.key()] = it.value().dump();
                }
            } else if (j["args"].is_string()) {
                try {
                    json a = json::parse(j["args"].get<std::string>());
                    if (a.is_object()) for (auto it=a.begin(); it!=a.end();++it) {
                        if (it.value().is_string()) call.args[it.key()] = it.value().get<std::string>();
                        else call.args[it.key()] = it.value().dump();
                    }
                } catch(...) { call.args["command"] = j["args"].get<std::string>(); }
            }
        }
        // parameters
        if (j.contains("parameters") && j["parameters"].is_object()) {
            for (auto it = j["parameters"].begin(); it != j["parameters"].end(); ++it) {
                if (it.value().is_string()) call.args[it.key()] = it.value().get<std::string>();
                else call.args[it.key()] = it.value().dump();
            }
        }
        // If no args but top-level has known keys, treat them as args
        if (call.args.empty()) {
            for (auto it = j.begin(); it != j.end(); ++it) {
                std::string k = it.key();
                if (k == "name" || k == "tool" || k == "id" || k == "type" || k == "function") continue;
                if (it.value().is_string()) call.args[k] = it.value().get<std::string>();
                else call.args[k] = it.value().dump();
            }
        }
    } catch (...) {
        // fallback: treat whole string as bash command
        call.name = "bash_exec";
        call.args["command"] = json_str;
        call.id = "call_0";
    }
    if (call.name.empty()) call.name = "bash_exec";
    return call;
}

std::vector<ToolCall> AgentToolDispatcher::parse_tool_calls_json(const std::string& json_str) {
    std::vector<ToolCall> out;
    try {
        json j = json::parse(json_str);
        if (j.is_array()) {
            for (const auto& item : j) {
                out.push_back(parse_tool_call_json(item.dump()));
            }
            return out;
        }
        if (j.contains("tool_calls") && j["tool_calls"].is_array()) {
            for (const auto& item : j["tool_calls"]) {
                out.push_back(parse_tool_call_json(item.dump()));
            }
            return out;
        }
    } catch (...) {}
    out.push_back(parse_tool_call_json(json_str));
    return out;
}

std::string ToolResult::to_json() const {
    json j;
    j["tool_call_id"] = tool_call_id;
    j["name"] = name;
    j["success"] = success;
    j["exit_code"] = exit_code;
    j["stdout"] = stdout_text;
    j["stderr"] = stderr_text;
    j["error"] = error;
    j["duration_ms"] = duration_ms;
    j["truncated"] = truncated;
    j["timed_out"] = timed_out;
    return j.dump();
}

} // namespace finn