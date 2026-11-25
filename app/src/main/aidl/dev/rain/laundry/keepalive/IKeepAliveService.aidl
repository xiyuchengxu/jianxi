package dev.rain.laundry.keepalive;

/** A fixed, self-package-only capability. No caller-supplied command or package is accepted. */
interface IKeepAliveService {
    boolean isUserWhitelisted() = 1;
    void addSelf() = 2;
    void removeSelf() = 3;
    boolean startGuard() = 4;
    void stopGuard() = 5;
    boolean isGuardRunning() = 6;
    void destroy() = 16777114;
}
