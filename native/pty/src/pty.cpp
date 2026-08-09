#include "pty.h"
#include <unistd.h>
#include <fcntl.h>
#include <poll.h>
#include <sys/ioctl.h>
#include <sys/wait.h>
#include <signal.h>
#include <cstring>
#include <cerrno>
#include <cstdlib>
#include <cstdio>

// Android doesn't have forkpty(), so we implement it using /dev/ptmx
namespace jcode {

// How long Pty::write waits for the guest to drain a full line-discipline buffer before giving up
// and reporting a short write. Generous for a shell that is reading (it drains in microseconds),
// short enough that a wedged guest can't block an input write for long.
static const int WRITE_DRAIN_TIMEOUT_MS = 2000;

// Open a PTY master/slave pair using /dev/ptmx
static int openPtyPair(int* master, int* slave, const struct winsize* ws) {
    // Open the PTY multiplexer
    *master = open("/dev/ptmx", O_RDWR | O_NOCTTY);
    if (*master < 0) return -1;

    // Grant access to the slave
    if (grantpt(*master) < 0 || unlockpt(*master) < 0) {
        ::close(*master);
        *master = -1;
        return -1;
    }

    // Get slave device name
    const char* slave_name = ptsname(*master);
    if (!slave_name) {
        ::close(*master);
        *master = -1;
        return -1;
    }

    // Open the slave device
    *slave = open(slave_name, O_RDWR | O_NOCTTY);
    if (*slave < 0) {
        ::close(*master);
        *master = -1;
        return -1;
    }

    // Set window size on master
    if (ws) {
        ioctl(*master, TIOCSWINSZ, ws);
    }

    return 0;
}

Pty::Pty() : master_fd_(-1), child_pid_(-1) {}

Pty::~Pty() {
    close();
}

bool Pty::create(const char* exe,
                 const std::vector<std::string>& argv,
                 const std::vector<std::string>& env,
                 const char* cwd,
                 int cols, int rows) {
    if (isOpen()) return false;

    struct winsize ws;
    ws.ws_col = cols;
    ws.ws_row = rows;
    ws.ws_xpixel = 0;
    ws.ws_ypixel = 0;

    int master, slave;
    if (openPtyPair(&master, &slave, &ws) < 0) {
        return false;
    }

    pid_t pid = fork();

    if (pid < 0) {
        // Fork failed
        ::close(master);
        ::close(slave);
        return false;
    }

    if (pid == 0) {
        // Child process

        // Close master fd in child
        ::close(master);

        // Create new session
        setsid();

        // Set controlling terminal
        ioctl(slave, TIOCSCTTY, 0);

        // Redirect stdin/stdout/stderr to slave
        dup2(slave, STDIN_FILENO);
        dup2(slave, STDOUT_FILENO);
        dup2(slave, STDERR_FILENO);

        // Close slave if it's not one of the standard fds
        if (slave > STDERR_FILENO) {
            ::close(slave);
        }

        // Change working directory if specified
        if (cwd && cwd[0] != '\0') {
            if (chdir(cwd) != 0) {
                _exit(127);
            }
        }

        // Set environment variables
        for (const auto& var : env) {
            putenv(const_cast<char*>(var.c_str()));
        }

        // Build argv array for execvp
        std::vector<char*> c_argv;
        for (const auto& arg : argv) {
            c_argv.push_back(const_cast<char*>(arg.c_str()));
        }
        c_argv.push_back(nullptr);

        // Execute the program
        execvp(exe, c_argv.data());

        // If exec fails, exit
        _exit(127);
    }

    // Parent process
    ::close(slave);  // Close slave fd in parent
    master_fd_ = master;
    child_pid_ = pid;

    // Set non-blocking mode on master fd
    int flags = fcntl(master_fd_, F_GETFL, 0);
    fcntl(master_fd_, F_SETFL, flags | O_NONBLOCK);

    return true;
}

int Pty::read(uint8_t* buffer, int maxLen) {
    if (!isOpen()) return -1;

    ssize_t n = ::read(master_fd_, buffer, maxLen);
    if (n < 0) {
        if (errno == EAGAIN || errno == EWOULDBLOCK) {
            return 0;  // No data available (non-blocking)
        }
        return -1;  // Error
    }
    if (n == 0) {
        // ::read() == 0 on the master fd means EOF: the child closed the slave / exited. This is
        // distinct from "no data yet" (EAGAIN), which is handled above and returns 0. Returning -1
        // here lets the Kotlin reader loop break and reap the session instead of spinning forever.
        return -1;  // EOF
    }
    return static_cast<int>(n);
}

int Pty::write(const uint8_t* data, int len) {
    if (!isOpen()) return -1;

    // Write everything, not just whatever one write() call happens to take.
    //
    // The master fd is O_NONBLOCK and a PTY's line discipline buffers only a few KB, so a single
    // write() of a large block returns short (or EAGAIN) and the remainder used to be dropped on
    // the floor with no error — the call even reported "success". That silently truncated any long
    // input, which is how the Setup terminal's heredoc'd toolchain scripts (the Android SDK one is
    // ~9 KB) arrived half-written and left the shell sitting forever inside an unterminated
    // heredoc. Loop instead, waiting for the guest to drain the buffer between chunks.
    int written = 0;
    while (written < len) {
        ssize_t n = ::write(master_fd_, data + written, len - written);
        if (n > 0) {
            written += static_cast<int>(n);
            continue;
        }
        if (n < 0 && errno == EINTR) continue;
        if (n < 0 && (errno == EAGAIN || errno == EWOULDBLOCK)) {
            struct pollfd pfd;
            pfd.fd = master_fd_;
            pfd.events = POLLOUT;
            pfd.revents = 0;
            int r = poll(&pfd, 1, WRITE_DRAIN_TIMEOUT_MS);
            if (r > 0) continue;
            if (r < 0 && errno == EINTR) continue;
            // Nothing drained within the timeout: the guest is not reading. Report the partial
            // count rather than spinning — the caller sees a short write instead of a hang.
            break;
        }
        return written > 0 ? written : -1;
    }
    return written;
}

bool Pty::resize(int cols, int rows) {
    if (!isOpen()) return false;

    struct winsize ws;
    ws.ws_col = cols;
    ws.ws_row = rows;
    ws.ws_xpixel = 0;
    ws.ws_ypixel = 0;

    return ioctl(master_fd_, TIOCSWINSZ, &ws) == 0;
}

int Pty::waitForExit() {
    if (child_pid_ < 0) return -1;

    int status;
    pid_t result = waitpid(child_pid_, &status, 0);

    if (result < 0) {
        return -1;
    }

    if (WIFEXITED(status)) {
        return WEXITSTATUS(status);
    } else if (WIFSIGNALED(status)) {
        return -WTERMSIG(status);  // Negative to indicate signal
    }

    return -1;
}

void Pty::close() {
    if (master_fd_ >= 0) {
        ::close(master_fd_);
        master_fd_ = -1;
    }

    if (child_pid_ >= 0) {
        // Try to terminate gracefully
        kill(child_pid_, SIGTERM);

        // Wait a bit, then force kill if needed
        int status;
        pid_t result = waitpid(child_pid_, &status, WNOHANG);
        if (result == 0) {
            usleep(100000);  // 100ms
            kill(child_pid_, SIGKILL);
            waitpid(child_pid_, &status, 0);
        }

        child_pid_ = -1;
    }
}

}  // namespace jcode
