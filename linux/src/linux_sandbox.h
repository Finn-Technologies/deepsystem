#pragma once

#include <string>
#include <vector>

struct LinuxSandboxResult {
    int exit_code = -1;
    std::string stdout_text;
    std::string stderr_text;
    bool timed_out = false;
};

class LinuxSandbox {
public:
    static bool bwrapAvailable();
    static bool unshareAvailable();

    // Run argv inside a rootless container when bwrap/unshare is available,
    // otherwise run directly (used for CI smoke tests).
    static LinuxSandboxResult run(const std::vector<std::string>& argv,
                                 const std::string& workdir,
                                 int timeout_ms,
                                 bool network_allowed);
};
