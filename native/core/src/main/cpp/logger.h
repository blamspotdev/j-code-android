#pragma once

#include <string>
#include <android/log.h>

namespace jcode {

/**
 * @brief Logging levels
 */
enum class LogLevel {
    VERBOSE = ANDROID_LOG_VERBOSE,
    DEBUG = ANDROID_LOG_DEBUG,
    INFO = ANDROID_LOG_INFO,
    WARN = ANDROID_LOG_WARN,
    ERROR = ANDROID_LOG_ERROR,
    FATAL = ANDROID_LOG_FATAL
};

/**
 * @brief Thread-safe logging system
 * 
 * Provides structured logging with automatic tag management and
 * optional file output for debugging.
 */
class Logger {
public:
    /**
     * @brief Initialize the logger with a default tag
     */
    static void init(const std::string& defaultTag = "JCodeCore");
    
    /**
     * @brief Set the minimum log level
     */
    static void setMinLevel(LogLevel level);
    
    /**
     * @brief Enable/disable file logging
     */
    static void setFileLogging(bool enabled, const std::string& filePath = "");
    
    /**
     * @brief Log a message at the specified level
     */
    static void log(LogLevel level, const char* tag, const char* format, ...);
    
    /**
     * @brief Convenience methods for each log level
     */
    static void verbose(const char* tag, const char* format, ...);
    static void debug(const char* tag, const char* format, ...);
    static void info(const char* tag, const char* format, ...);
    static void warn(const char* tag, const char* format, ...);
    static void error(const char* tag, const char* format, ...);
    static void fatal(const char* tag, const char* format, ...);
    
    /**
     * @brief Log with the default tag
     */
    static void log(LogLevel level, const char* format, ...);

private:
    static std::string defaultTag_;
    static LogLevel minLevel_;
    static bool fileLoggingEnabled_;
    static std::string logFilePath_;
};

// Convenience macros for logging
#define LOG_V(tag, ...) jcode::Logger::verbose(tag, __VA_ARGS__)
#define LOG_D(tag, ...) jcode::Logger::debug(tag, __VA_ARGS__)
#define LOG_I(tag, ...) jcode::Logger::info(tag, __VA_ARGS__)
#define LOG_W(tag, ...) jcode::Logger::warn(tag, __VA_ARGS__)
#define LOG_E(tag, ...) jcode::Logger::error(tag, __VA_ARGS__)
#define LOG_F(tag, ...) jcode::Logger::fatal(tag, __VA_ARGS__)

} // namespace jcode
