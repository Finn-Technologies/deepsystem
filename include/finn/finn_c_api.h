#ifndef FINN_C_API_H
#define FINN_C_API_H

#include <stddef.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

#if defined(_WIN32)
#    if defined(FINN_BUILD_SHARED)
#        define FINN_API __declspec(dllexport)
#    else
#        define FINN_API __declspec(dllimport)
#    endif
#else
#    define FINN_API __attribute__((visibility("default")))
#endif

typedef struct finn_agent_dispatcher_t finn_agent_dispatcher_t;

FINN_API finn_agent_dispatcher_t* finn_agent_dispatcher_create(const char* workspace_dir, int network_allowed, int use_proot, const char* rootfs_path);
FINN_API void finn_agent_dispatcher_destroy(finn_agent_dispatcher_t* d);
FINN_API void finn_agent_dispatcher_set_permissions(finn_agent_dispatcher_t* d, int network_allowed, int process_spawn_allowed, int file_write_allowed, int timeout_ms, size_t max_output_bytes);
FINN_API char* finn_agent_dispatch_json(finn_agent_dispatcher_t* d, const char* tool_call_json);
FINN_API char* finn_agent_dispatch_tool(finn_agent_dispatcher_t* d, const char* name, const char* args_json);
FINN_API char* finn_agent_available_tools_json(finn_agent_dispatcher_t* d);
FINN_API char* finn_agent_audit_log_json(finn_agent_dispatcher_t* d);
FINN_API void finn_agent_clear_audit_log(finn_agent_dispatcher_t* d);
FINN_API int finn_agent_has_rootfs(finn_agent_dispatcher_t* d);
FINN_API int finn_agent_proot_available(finn_agent_dispatcher_t* d);

FINN_API void finn_free_string(char* s);

#ifdef __cplusplus
}
#endif

#endif /* FINN_C_API_H */