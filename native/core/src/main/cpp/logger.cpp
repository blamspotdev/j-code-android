#include "logger.h"
#include <cstdarg>
#include <cstdio>
#include <mutex>
#include <fstream>

namespace jcode {

std::string Logger::defaultTag_ = "JCodeCore";
LogLevel Logger::minLevel_ = LogLevel::DEBUG;
bool Logger::fileLoggingEnabled_ = false;
std::string Logger::logFilePath_ = "";

static std::mutex logMutex;

void Logger::init(const std::string& defaultTag) {
    std::lock_guard<std::mutex> lock(logMutex);
    defaultTag_ = defaultTag;
}

void Logger::setMinLevel(LogLevel level) {
    std::lock_guard<std::mutex> lock(logMutex);
    minLevel_ = level;
}

void Logger::setFileLogging(bool enabled, const std::string& filePath) {
    std::lock_guard<std::mutex> lock(logMutex);
    fileLoggingEnabled_ = enabled;
    logFilePath_ = filePath;
}

void Logger::log(LogLevel level, const char* tag, const char* format, ...) {
    if (level < minLevel_) {
        return;
    }
    
    va_list args;
    va_start(args, format);
    
    // Log to Android logcat
    __android_log_vprint(static_cast<int>(level), tag, format, args);
    
    // Optionally log to file
    if (fileLoggingEnabled_ && !logFilePath_.empty()) {
        std::lock_guard<std::mutex> lock(logMutex);
        std::ofstream file(logFilePath_, std::ios::app);
        if (file.is_open()) {
            char buffer[4096];
            va_list argsCopy;
            va_copy(argsCopy, args);
            vsnprintf(buffer, sizeof(buffer), format, argsCopy);
            va_end(argsCopy);
            
            const char* levelStr = "UNKNOWN";
            switch (level) {
                case LogLevel::VERBOSE: levelStr = "VERBOSE"; break;
                case LogLevel::DEBUG: levelStr = "DEBUG"; break;
                case LogLevel::INFO: levelStr = "INFO"; break;
                case LogLevel::WARN: levelStr = "WARN"; break;
                case LogLevel::ERROR: levelStr = "ERROR"; break;
                case LogLevel::FATAL: levelStr = "FATAL"; break;
            }
            
            file << "[" << levelStr << "] [" << tag << "] " << buffer << std::endl;
        }
    }
    
    va_end(args);
}

void Logger::verbose(const char* tag, const char* format, ...) {
    va_list args;
    va_start(args, format);
    log(LogLevel::VERBOSE, tag, format, args);
    va_end(args);
}

void Logger::debug(const char* tag, const char* format, ...) {
    va_list args;
    va_start(args, format);
    log(LogLevel::DEBUG, tag, format, args);
    va_end(args);
}

void Logger::info(const char* tag, const char* format, ...) {
    va_list args;
    va_start(args, format);
    log(LogLevel::INFO, tag, format, args);
    va_end(args);
}

void Logger::warn(const char* tag, const char* format, ...) {
    va_list args;
    va_start(args, format);
    log(LogLevel::WARN, tag, format, args);
    va_end(args);
}

void Logger::error(const char* tag, const char* format, ...) {
    va_list args;
    va_start(args, format);
    log(LogLevel::ERROR, tag, format, args);
    va_end(args);
}

void Logger::fatal(const char* tag, const char* format, ...) {
    va_list args;
    va_start(args, format);
    log(LogLevel::FATAL, tag, format, args);
    va_end(args);
}

void Logger::log(LogLevel level, const char* format, ...) {
    va_list args;
    va_start(args, format);
    log(level, defaultTag_.c_str(), format, args);
    va_end(args);
}

} // namespace jcode
