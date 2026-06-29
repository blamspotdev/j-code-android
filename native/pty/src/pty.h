#pragma once

#include <cstdint>
#include <string>
#include <vector>
#include <sys/types.h>

namespace jcode {

/**
 * PTY (pseudo-terminal) wrapper using forkpty/openpty.
 * Provides a full-duplex byte stream to a child process.
 */
class Pty {
public:
    Pty();
    ~Pty();

    /**
     * Create a PTY and fork a child process.
     * @param exe Path to executable
     * @param argv Argument list (null-terminated)
     * @param env Environment variables (null-terminated, KEY=VALUE format)
     * @param cwd Working directory (nullptr for inherit)
     * @param cols Initial terminal columns
     * @param rows Initial terminal rows
     * @return true on success, false on failure
     */
    bool create(const char* exe,
                const std::vector<std::string>& argv,
                const std::vector<std::string>& env,
                const char* cwd,
                int cols, int rows);

    /**
     * Read bytes from the PTY (blocking).
     * @param buffer Output buffer
     * @param maxLen Maximum bytes to read
     * @return Number of bytes read, 0 on EOF, -1 on error
     */
    int read(uint8_t* buffer, int maxLen);

    /**
     * Write bytes to the PTY.
     * @param data Input data
     * @param len Number of bytes to write
     * @return Number of bytes written, -1 on error
     */
    int write(const uint8_t* data, int len);

    /**
     * Resize the terminal.
     * @param cols New column count
     * @param rows New row count
     * @return true on success
     */
    bool resize(int cols, int rows);

    /**
     * Wait for the child process to exit.
     * @return Exit status, or -1 on error
     */
    int waitForExit();

    /**
     * Close the PTY and kill the child process.
     */
    void close();

    /**
     * Check if the PTY is still open.
     */
    bool isOpen() const { return master_fd_ >= 0; }

    /**
     * Get the master file descriptor.
     */
    int masterFd() const { return master_fd_; }

    /**
     * Get the child process ID.
     */
    pid_t childPid() const { return child_pid_; }

private:
    int master_fd_;
    pid_t child_pid_;
};

}  // namespace jcode
