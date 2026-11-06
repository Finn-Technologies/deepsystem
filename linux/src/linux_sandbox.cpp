#include "linux_sandbox.h"

#include <sys/types.h>
#include <sys/wait.h>
#include <unistd.h>
#include <fcntl.h>
#include <poll.h>
#include <signal.h>
#include <errno.h>

#include <algorithm>
#include <chrono>
#include <cstring>
#include <iostream>
#include <thread>

static bool commandExists(const char* name) {
    std::string cmd = "command -v ";
    cmd += name;
    cmd += " >/dev/null 2>&1";
    return system(cmd.c_str()) == 0;
}

bool LinuxSandbox::bwrapAvailable() { return commandExists("bwrap"); }
bool LinuxSandbox::unshareAvailable() { return commandExists("unshare"); }

static std::vector<char*> toArgv(const std::vector<std::string>& args) {
    std::vector<char*> v;
    for (const auto& a : args) v.push_back(const_cast<char*>(a.c_str()));
    v.push_back(nullptr);
    return v;
}

LinuxSandboxResult LinuxSandbox::run(const std::vector<std::string>& argv,
                                    const std::string& workdir,
                                    int timeout_ms,
                                    bool network_allowed) {
    LinuxSandboxResult result;
    if (argv.empty()) { result.exit_code = -1; result.stderr_text = "empty argv"; return result; }

    // Build the actual command line. Prefer bwrap, then unshare, then direct.
    std::vector<std::string> cmdline;
    if (bwrapAvailable()) {
        cmdline.push_back("bwrap");
        cmdline.push_back("--die-with-parent");
        cmdline.push_back("--new-session");
        cmdline.push_back("--proc");
        cmdline.push_back("/proc");
        cmdline.push_back("--dev");
        cmdline.push_back("/dev");
        cmdline.push_back("--tmpfs");
        cmdline.push_back("/tmp");
        cmdline.push_back("--ro-bind");
        cmdline.push_back("/");
        cmdline.push_back("/");
        if (!network_allowed) {
            cmdline.push_back("--unshare-net");
        }
        cmdline.push_back("--unshare-pid");
        cmdline.push_back("--unshare-ipc");
        cmdline.push_back("--unshare-uts");
        cmdline.push_back("--chdir");
        cmdline.push_back(workdir.empty() ? "/" : workdir);
        for (const auto& a : argv) cmdline.push_back(a);
    } else if (unshareAvailable()) {
        cmdline.push_back("unshare");
        cmdline.push_back("--user");
        cmdline.push_back("--pid");
        cmdline.push_back("--fork");
        cmdline.push_back("--mount-proc");
        if (!network_allowed) cmdline.push_back("--net");
        cmdline.push_back("--");
        for (const auto& a : argv) cmdline.push_back(a);
    } else {
        for (const auto& a : argv) cmdline.push_back(a);
    }

    int out_pipe[2], err_pipe[2];
    if (pipe(out_pipe) != 0 || pipe(err_pipe) != 0) {
        result.exit_code = -1;
        result.stderr_text = "pipe() failed";
        return result;
    }

    pid_t pid = fork();
    if (pid < 0) {
        result.exit_code = -1;
        result.stderr_text = "fork() failed";
        return result;
    }
    if (pid == 0) {
        // child
        close(out_pipe[0]);
        close(err_pipe[0]);
        dup2(out_pipe[1], STDOUT_FILENO);
        dup2(err_pipe[1], STDERR_FILENO);
        close(out_pipe[1]);
        close(err_pipe[1]);
        if (!workdir.empty()) chdir(workdir.c_str());
        auto av = toArgv(cmdline);
        execvp(av[0], av.data());
        // exec failed
        const char* msg = "execvp failed\n";
        write(STDERR_FILENO, msg, strlen(msg));
        _exit(127);
    }

    close(out_pipe[1]);
    close(err_pipe[1]);

    // non-blocking reads
    fcntl(out_pipe[0], F_SETFL, O_NONBLOCK);
    fcntl(err_pipe[0], F_SETFL, O_NONBLOCK);

    std::string out, err;
    auto start = std::chrono::steady_clock::now();
    bool done = false;
    while (!done) {
        auto elapsed = std::chrono::duration_cast<std::chrono::milliseconds>(
            std::chrono::steady_clock::now() - start).count();
        if (timeout_ms > 0 && elapsed >= timeout_ms) {
            kill(pid, SIGKILL);
            waitpid(pid, nullptr, 0);
            result.timed_out = true;
            result.exit_code = -1;
            result.stdout_text = out;
            result.stderr_text = err + "\n[timeout]";
            close(out_pipe[0]);
            close(err_pipe[0]);
            return result;
        }

        struct pollfd fds[2];
        fds[0].fd = out_pipe[0]; fds[0].events = POLLIN;
        fds[1].fd = err_pipe[0]; fds[1].events = POLLIN;
        int rc = poll(fds, 2, 50);
        if (rc > 0) {
            for (int i = 0; i < 2; ++i) {
                if (fds[i].revents & POLLIN) {
                    char buf[4096];
                    ssize_t n = read(fds[i].fd, buf, sizeof(buf));
                    if (n > 0) {
                        if (i == 0) out.append(buf, n); else err.append(buf, n);
                    } else if (n == 0) {
                        // eof
                    }
                }
            }
        }
        // check child exit
        int status = 0;
        pid_t r = waitpid(pid, &status, WNOHANG);
        if (r == pid) {
            // drain remaining
            char buf[4096];
            ssize_t n;
            while ((n = read(out_pipe[0], buf, sizeof(buf))) > 0) out.append(buf, n);
            while ((n = read(err_pipe[0], buf, sizeof(buf))) > 0) err.append(buf, n);
            done = true;
            if (WIFEXITED(status)) result.exit_code = WEXITSTATUS(status);
            else if (WIFSIGNALED(status)) result.exit_code = 128 + WTERMSIG(status);
            else result.exit_code = -1;
        }
    }

    close(out_pipe[0]);
    close(err_pipe[0]);
    result.stdout_text = out;
    result.stderr_text = err;
    return result;
}
