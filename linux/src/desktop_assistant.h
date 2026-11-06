#pragma once

#include <string>
#include <functional>

// Desktop assistant: global hotkey + StatusNotifierItem tray integration.
// On platforms without D-Bus, all operations are no-ops that log intent.
class DesktopAssistant {
public:
    using ActivationCallback = std::function<void()>;

    DesktopAssistant();
    ~DesktopAssistant();

    void setActivationCallback(ActivationCallback cb);

    // Start tray icon + register global hotkey (best effort).
    void start();
    void stop();

    bool isRunning() const { return running_; }

private:
    ActivationCallback cb_;
    bool running_ = false;

#ifdef FINN_HAVE_DBUS
    // Opaque D-Bus connection handle (dbus_connection*).
    void* connection_ = nullptr;
#endif
};
