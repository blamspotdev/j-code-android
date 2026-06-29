#pragma once

#include <string>
#include <memory>
#include <optional>
#include <vector>
#include <unordered_map>

namespace jcode {

/**
 * @brief Configuration value types
 */
enum class ConfigValueType {
    Null,
    Boolean,
    Integer,
    Float,
    String,
    Array,
    Object
};

/**
 * @brief Configuration value wrapper
 */
class ConfigValue {
public:
    ConfigValue();
    explicit ConfigValue(bool value);
    explicit ConfigValue(int64_t value);
    explicit ConfigValue(double value);
    explicit ConfigValue(const std::string& value);
    explicit ConfigValue(const char* value);
    
    ConfigValueType getType() const { return type_; }
    
    bool isNull() const { return type_ == ConfigValueType::Null; }
    bool isBoolean() const { return type_ == ConfigValueType::Boolean; }
    bool isInteger() const { return type_ == ConfigValueType::Integer; }
    bool isFloat() const { return type_ == ConfigValueType::Float; }
    bool isString() const { return type_ == ConfigValueType::String; }
    bool isArray() const { return type_ == ConfigValueType::Array; }
    bool isObject() const { return type_ == ConfigValueType::Object; }
    
    bool asBoolean(bool defaultValue = false) const;
    int64_t asInteger(int64_t defaultValue = 0) const;
    double asFloat(double defaultValue = 0.0) const;
    std::string asString(const std::string& defaultValue = "") const;
    
    // Array operations
    size_t arraySize() const;
    ConfigValue arrayGet(size_t index) const;
    void arrayPush(const ConfigValue& value);
    
    // Object operations
    bool objectHas(const std::string& key) const;
    ConfigValue objectGet(const std::string& key) const;
    void objectSet(const std::string& key, const ConfigValue& value);
    std::vector<std::string> objectKeys() const;

private:
    ConfigValueType type_;
    
    union {
        bool boolValue_;
        int64_t intValue_;
        double floatValue_;
    };
    
    std::string stringValue_;
    std::vector<ConfigValue> arrayValue_;
    std::unordered_map<std::string, ConfigValue> objectValue_;
};

/**
 * @brief Configuration service for YAML parsing and management
 */
class ConfigService {
public:
    ConfigService();
    ~ConfigService();
    
    /**
     * @brief Parse YAML from a string
     * @return true if parsing succeeded
     */
    bool parseYaml(const std::string& yamlContent);
    
    /**
     * @brief Parse YAML from a file
     * @return true if parsing succeeded
     */
    bool parseYamlFile(const std::string& filePath);
    
    /**
     * @brief Serialize current config to YAML string
     */
    std::string toYaml() const;
    
    /**
     * @brief Get a configuration value by path (e.g., "editor.fontSize")
     */
    ConfigValue get(const std::string& path) const;
    
    /**
     * @brief Set a configuration value by path
     */
    void set(const std::string& path, const ConfigValue& value);
    
    /**
     * @brief Check if a path exists
     */
    bool has(const std::string& path) const;
    
    /**
     * @brief Remove a configuration value
     */
    void remove(const std::string& path);
    
    /**
     * @brief Clear all configuration
     */
    void clear();
    
    /**
     * @brief Get the root configuration object
     */
    const ConfigValue& root() const { return root_; }

private:
    ConfigValue root_;
    
    /**
     * @brief Parse a path string into components
     */
    std::vector<std::string> parsePath(const std::string& path) const;
};

} // namespace jcode
