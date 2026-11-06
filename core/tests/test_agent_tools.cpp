#include <gtest/gtest.h>
#include "finn/agent_tools.h"
#include "finn/finn_c_api.h"

#include <filesystem>
#include <fstream>
#include <string>

namespace fs = std::filesystem;

static std::string tmpdir() {
    std::string d = "/tmp/finn_agent_test";
    fs::create_directories(d);
    return d;
}

TEST(AgentToolsTest, ParseToolCallJsonVariants) {
    using finn::AgentToolDispatcher;
    auto c1 = AgentToolDispatcher::parse_tool_call_json(R"({"name":"bash_exec","arguments":{"command":"echo hi"}})");
    EXPECT_EQ(c1.name, "bash_exec");
    EXPECT_EQ(c1.get("command"), "echo hi");

    auto c2 = AgentToolDispatcher::parse_tool_call_json(R"({"tool":"file_read","args":{"path":"a.txt"}})");
    EXPECT_EQ(c2.name, "file_read");
    EXPECT_EQ(c2.get("path"), "a.txt");

    auto c3 = AgentToolDispatcher::parse_tool_call_json(R"({"type":"function","function":{"name":"web_fetch","arguments":"{\"url\":\"https://x\"}"}})");
    EXPECT_EQ(c3.name, "web_fetch");
    EXPECT_EQ(c3.get("url"), "https://x");

    auto calls = AgentToolDispatcher::parse_tool_calls_json(R"([{"name":"bash_exec","arguments":{"command":"ls"}},{"name":"file_read","arguments":{"path":"b"}}])");
    EXPECT_EQ(calls.size(), 2u);
}

TEST(AgentToolsTest, BashExecSuccess) {
    finn::SandboxConfig cfg;
    cfg.workspace_dir = tmpdir();
    finn::AgentToolDispatcher d(cfg);
    finn::ToolCall c;
    c.id = "1";
    c.name = "bash_exec";
    c.args["command"] = "echo hello";
    auto r = d.dispatch(c);
    EXPECT_TRUE(r.success);
    EXPECT_EQ(r.exit_code, 0);
    EXPECT_NE(r.stdout_text.find("hello"), std::string::npos);
}

TEST(AgentToolsTest, FileReadWriteScoped) {
    std::string wd = tmpdir() + "/ws1";
    fs::create_directories(wd);
    finn::SandboxConfig cfg;
    cfg.workspace_dir = wd;
    finn::AgentToolDispatcher d(cfg);

    finn::ToolCall w;
    w.id = "w"; w.name = "file_write";
    w.args["path"] = "sub/out.txt";
    w.args["content"] = "data123";
    auto wr = d.dispatch(w);
    EXPECT_TRUE(wr.success);

    finn::ToolCall r;
    r.id = "r"; r.name = "file_read";
    r.args["path"] = "sub/out.txt";
    auto rr = d.dispatch(r);
    EXPECT_TRUE(rr.success);
    EXPECT_EQ(rr.stdout_text, "data123");

    // path traversal blocked
    finn::ToolCall bad;
    bad.id = "b"; bad.name = "file_read";
    bad.args["path"] = "../etc/passwd";
    auto br = d.dispatch(bad);
    EXPECT_FALSE(br.success);
    EXPECT_NE(br.error.find("outside workspace"), std::string::npos);
}

TEST(AgentToolsTest, NetworkGating) {
    finn::SandboxConfig cfg;
    cfg.workspace_dir = tmpdir();
    cfg.permissions.network_allowed = false;
    finn::AgentToolDispatcher d(cfg);
    finn::ToolCall c;
    c.id = "n"; c.name = "web_fetch";
    c.args["url"] = "https://example.com";
    auto r = d.dispatch(c);
    EXPECT_FALSE(r.success);
    EXPECT_NE(r.error.find("network not allowed"), std::string::npos);

    cfg.permissions.network_allowed = true;
    finn::AgentToolDispatcher d2(cfg);
    finn::ToolCall c2 = c;
    auto r2 = d2.dispatch(c2);
    // may fail due to no network, but not permission error
    EXPECT_TRUE(r2.error.empty() || r2.error.find("network not allowed") == std::string::npos);
}

TEST(AgentToolsTest, TimeoutEnforcement) {
    finn::SandboxConfig cfg;
    cfg.workspace_dir = tmpdir();
    cfg.permissions.timeout_ms = 200;
    finn::AgentToolDispatcher d(cfg);
    finn::ToolCall c;
    c.id = "t"; c.name = "bash_exec";
    c.args["command"] = "sleep 5";
    auto r = d.dispatch(c);
    EXPECT_FALSE(r.success);
    EXPECT_TRUE(r.timed_out);
}

TEST(AgentToolsTest, OutputTruncation) {
    finn::SandboxConfig cfg;
    cfg.workspace_dir = tmpdir();
    cfg.permissions.max_output_bytes = 16;
    finn::AgentToolDispatcher d(cfg);
    finn::ToolCall c;
    c.id = "o"; c.name = "bash_exec";
    c.args["command"] = "python3 -c \"print('A'*100)\"";
    auto r = d.dispatch(c);
    EXPECT_TRUE(r.truncated);
    EXPECT_LE(r.stdout_text.size(), 16u);
}

TEST(AgentToolsTest, CommandAllowlist) {
    finn::SandboxConfig cfg;
    cfg.workspace_dir = tmpdir();
    cfg.permissions.allowed_commands = {"echo"};
    finn::AgentToolDispatcher d(cfg);
    finn::ToolCall c1;
    c1.id = "a"; c1.name = "bash_exec";
    c1.args["command"] = "echo ok";
    auto r1 = d.dispatch(c1);
    EXPECT_TRUE(r1.success);

    finn::ToolCall c2;
    c2.id = "b"; c2.name = "bash_exec";
    c2.args["command"] = "ls";
    auto r2 = d.dispatch(c2);
    EXPECT_FALSE(r2.success);
    EXPECT_NE(r2.error.find("not allowed"), std::string::npos);
}

TEST(AgentToolsTest, AuditLog) {
    finn::SandboxConfig cfg;
    cfg.workspace_dir = tmpdir();
    cfg.audit_log_path = tmpdir() + "/audit.log";
    finn::AgentToolDispatcher d(cfg);
    finn::ToolCall c;
    c.id = "a"; c.name = "bash_exec";
    c.args["command"] = "echo x";
    d.dispatch(c);
    EXPECT_EQ(d.audit_log().size(), 1u);
    EXPECT_TRUE(fs::exists(cfg.audit_log_path));
}

TEST(AgentToolsTest, CAbiDispatch) {
    std::string wd = tmpdir() + "/cabi";
    fs::create_directories(wd);
    finn_agent_dispatcher_t* d = finn_agent_dispatcher_create(wd.c_str(), 0, 0, nullptr);
    ASSERT_NE(d, nullptr);
    char* tools = finn_agent_available_tools_json(d);
    ASSERT_NE(tools, nullptr);
    std::string t(tools);
    EXPECT_NE(t.find("bash_exec"), std::string::npos);
    finn_free_string(tools);

    char* res = finn_agent_dispatch_json(d, R"({"name":"bash_exec","arguments":{"command":"echo abi"}})");
    ASSERT_NE(res, nullptr);
    std::string r(res);
    EXPECT_NE(r.find("abi"), std::string::npos);
    finn_free_string(res);

    finn_agent_dispatcher_destroy(d);
}