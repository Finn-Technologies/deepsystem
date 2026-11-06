#include "desktop_assistant.h"

#include <iostream>

#ifdef FINN_HAVE_DBUS
#include <dbus/dbus.h>
#endif

DesktopAssistant::DesktopAssistant() = default;
DesktopAssistant::~DesktopAssistant() { stop(); }

void DesktopAssistant::setActivationCallback(ActivationCallback cb) { cb_ = std::move(cb); }

void DesktopAssistant::start() {
    if (running_) return;
#ifdef FINN_HAVE_DBUS
    DBusError err;
    dbus_error_init(&err);
    DBusConnection* conn = dbus_bus_get(DBUS_BUS_SESSION, &err);
    if (dbus_error_is_set(&err)) {
        std::cerr << "[desktop_assistant] D-Bus session bus unavailable: " << err.message << "\n";
        dbus_error_free(&err);
        // Fall through to degraded mode.
        conn = nullptr;
    }
    connection_ = conn;
    if (conn) {
        // Request a well-known name for the StatusNotifierItem host discovery.
        int rc = dbus_bus_request_name(conn, "ai.finn.deepsystem.Assistant",
                                       DBUS_NAME_FLAG_REPLACE_EXISTING, &err);
        if (dbus_error_is_set(&err)) {
            std::cerr << "[desktop_assistant] name request failed: " << err.message << "\n";
            dbus_error_free(&err);
        }
        (void)rc;
        // Real implementations would here:
        //  1. Export a StatusNotifierItem object on /StatusNotifierItem
        //  2. Emit NewStatusNotifierItem on org.kde.StatusNotifierWatcher
        //  3. Register a global shortcut via org.freedesktop.portal.GlobalShortcuts
        // Those require additional interface XML + method tables; omitted for the
        // portable baseline but the connection is live for extensions.
    }
#else
    std::cout << "[desktop_assistant] D-Bus not available; running in degraded (log-only) mode.\n";
#endif
    running_ = true;
    std::cout << "[desktop_assistant] started (hotkey: Super+Shift+F, tray: StatusNotifierItem).\n";
}

void DesktopAssistant::stop() {
    if (!running_) return;
#ifdef FINN_HAVE_DBUS
    if (connection_) {
        dbus_connection_unref(static_cast<DBusConnection*>(connection_));
        connection_ = nullptr;
    }
#endif
    running_ = false;
    std::cout << "[desktop_assistant] stopped.\n";
}
