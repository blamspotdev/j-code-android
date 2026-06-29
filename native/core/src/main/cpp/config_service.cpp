#include "config_service.h"
#include "logger.h"
#include <yaml-cpp/yaml.h>
#include <fstream>
#include <sstream>
#include <algorithm>
#include <functional>

namespace jcode {

// ConfigValue implementation

ConfigValue::ConfigValue() : type_(ConfigValueType::Null), boolValue_(false) {}

ConfigValue::ConfigValue(bool value) : type_(ConfigValueType::Boolean), boolValue_(value) {}

ConfigValue::ConfigValue(int64_t value) : type_(ConfigValueType::Integer), intValue_(value) {}

ConfigValue::ConfigValue(double value) : type_(ConfigValueType::Float), floatValue_(value) {}

ConfigValue::ConfigValue(const std::string& value) : type_(ConfigValueType::String), boolValue_(false), stringValue_(value) {}

ConfigValue::ConfigValue(const char* value) : type_(ConfigValueType::String), boolValue_(false), stringValue_(value) {}

bool ConfigValue::asBoolean(bool defaultValue) const {
    if (type_ == ConfigValueType::Boolean) return boolValue_;
    if (type_ == ConfigValueType::Integer) return intValue_ != 0;
    if (type_ == ConfigValueType::String) {
        std::string lower = stringValue_;
        std::transform(lower.begin(), lower.end(), lower.begin(), ::tolower);
        return lower == "true" || lower == "yes" || lower == "1";
    }
    return defaultValue;
}

int64_t ConfigValue::asInteger(int64_t defaultValue) const {
    if (type_ == ConfigValueType::Integer) return intValue_;
    if (type_ == ConfigValueType::Float) return static_cast<int64_t>(floatValue_);
    if (type_ == ConfigValueType::Boolean) return boolValue_ ? 1 : 0;
    if (type_ == ConfigValueType::String) {
        try {
            return std::stoll(stringValue_);
        } catch (...) {
            return defaultValue;
        }
    }
    return defaultValue;
}

double ConfigValue::asFloat(double defaultValue) const {
    if (type_ == ConfigValueType::Float) return floatValue_;
    if (type_ == ConfigValueType::Integer) return static_cast<double>(intValue_);
    if (type_ == ConfigValueType::String) {
        try {
            return std::stod(stringValue_);
        } catch (...) {
            return defaultValue;
        }
    }
    return defaultValue;
}

std::string ConfigValue::asString(const std::string& defaultValue) const {
    if (type_ == ConfigValueType::String) return stringValue_;
    if (type_ == ConfigValueType::Boolean) return boolValue_ ? "true" : "false";
    if (type_ == ConfigValueType::Integer) return std::to_string(intValue_);
    if (type_ == ConfigValueType::Float) return std::to_string(floatValue_);
    return defaultValue;
}

size_t ConfigValue::arraySize() const {
    return type_ == ConfigValueType::Array ? arrayValue_.size() : 0;
}

ConfigValue ConfigValue::arrayGet(size_t index) const {
    if (type_ == ConfigValueType::Array && index < arrayValue_.size()) {
        return arrayValue_[index];
    }
    return ConfigValue();
}

void ConfigValue::arrayPush(const ConfigValue& value) {
    if (type_ == ConfigValueType::Null) {
        type_ = ConfigValueType::Array;
    }
    if (type_ == ConfigValueType::Array) {
        arrayValue_.push_back(value);
    }
}

bool ConfigValue::objectHas(const std::string& key) const {
    return type_ == ConfigValueType::Object && objectValue_.find(key) != objectValue_.end();
}

ConfigValue ConfigValue::objectGet(const std::string& key) const {
    if (type_ == ConfigValueType::Object) {
        auto it = objectValue_.find(key);
        if (it != objectValue_.end()) {
            return it->second;
        }
    }
    return ConfigValue();
}

void ConfigValue::objectSet(const std::string& key, const ConfigValue& value) {
    if (type_ == ConfigValueType::Null) {
        type_ = ConfigValueType::Object;
    }
    if (type_ == ConfigValueType::Object) {
        objectValue_[key] = value;
    }
}

std::vector<std::string> ConfigValue::objectKeys() const {
    std::vector<std::string> keys;
    if (type_ == ConfigValueType::Object) {
        for (const auto& pair : objectValue_) {
            keys.push_back(pair.first);
        }
    }
    return keys;
}

// ConfigService implementation

ConfigService::ConfigService() {}

ConfigService::~ConfigService() {}

// Helper to convert YAML::Node to ConfigValue
static ConfigValue yamlNodeToConfigValue(const YAML::Node& node) {
    if (!node.IsDefined() || node.IsNull()) {
        return ConfigValue();
    }
    
    if (node.IsScalar()) {
        std::string scalar = node.as<std::string>();
        
        // Try to parse as boolean
        if (scalar == "true" || scalar == "True" || scalar == "TRUE") {
            return ConfigValue(true);
        }
        if (scalar == "false" || scalar == "False" || scalar == "FALSE") {
            return ConfigValue(false);
        }
        
        // Try to parse as integer
        try {
            size_t pos;
            int64_t intVal = std::stoll(scalar, &pos);
            if (pos == scalar.length()) {
                return ConfigValue(intVal);
            }
        } catch (...) {}
        
        // Try to parse as float
        try {
            size_t pos;
            double floatVal = std::stod(scalar, &pos);
            if (pos == scalar.length()) {
                return ConfigValue(floatVal);
            }
        } catch (...) {}
        
        // Default to string
        return ConfigValue(scalar);
    }
    
    if (node.IsSequence()) {
        ConfigValue array;
        for (const auto& item : node) {
            array.arrayPush(yamlNodeToConfigValue(item));
        }
        return array;
    }
    
    if (node.IsMap()) {
        ConfigValue object;
        for (const auto& pair : node) {
            std::string key = pair.first.as<std::string>();
            object.objectSet(key, yamlNodeToConfigValue(pair.second));
        }
        return object;
    }
    
    return ConfigValue();
}

// Helper to convert ConfigValue to YAML::Node
static YAML::Node configValueToYamlNode(const ConfigValue& value) {
    YAML::Node node;
    
    switch (value.getType()) {
        case ConfigValueType::Null:
            return node;
            
        case ConfigValueType::Boolean:
            node = value.asBoolean();
            break;
            
        case ConfigValueType::Integer:
            node = value.asInteger();
            break;
            
        case ConfigValueType::Float:
            node = value.asFloat();
            break;
            
        case ConfigValueType::String:
            node = value.asString();
            break;
            
        case ConfigValueType::Array:
            for (size_t i = 0; i < value.arraySize(); ++i) {
                node.push_back(configValueToYamlNode(value.arrayGet(i)));
            }
            break;
            
        case ConfigValueType::Object:
            for (const auto& key : value.objectKeys()) {
                node[key] = configValueToYamlNode(value.objectGet(key));
            }
            break;
    }
    
    return node;
}

bool ConfigService::parseYaml(const std::string& yamlContent) {
    try {
        YAML::Node node = YAML::Load(yamlContent);
        root_ = yamlNodeToConfigValue(node);
        return true;
    } catch (const YAML::Exception& e) {
        LOG_E("ConfigService", "Failed to parse YAML: %s", e.what());
        return false;
    }
}

bool ConfigService::parseYamlFile(const std::string& filePath) {
    try {
        YAML::Node node = YAML::LoadFile(filePath);
        root_ = yamlNodeToConfigValue(node);
        return true;
    } catch (const YAML::Exception& e) {
        LOG_E("ConfigService", "Failed to parse YAML file '%s': %s", filePath.c_str(), e.what());
        return false;
    }
}

std::string ConfigService::toYaml() const {
    try {
        YAML::Node node = configValueToYamlNode(root_);
        YAML::Emitter emitter;
        emitter << node;
        return emitter.c_str();
    } catch (const YAML::Exception& e) {
        LOG_E("ConfigService", "Failed to serialize to YAML: %s", e.what());
        return "";
    }
}

std::vector<std::string> ConfigService::parsePath(const std::string& path) const {
    std::vector<std::string> components;
    std::istringstream stream(path);
    std::string component;
    
    while (std::getline(stream, component, '.')) {
        if (!component.empty()) {
            components.push_back(component);
        }
    }
    
    return components;
}

ConfigValue ConfigService::get(const std::string& path) const {
    auto components = parsePath(path);
    ConfigValue current = root_;
    
    for (const auto& component : components) {
        if (!current.isObject() || !current.objectHas(component)) {
            return ConfigValue();
        }
        current = current.objectGet(component);
    }
    
    return current;
}

void ConfigService::set(const std::string& path, const ConfigValue& value) {
    auto components = parsePath(path);
    if (components.empty()) {
        root_ = value;
        return;
    }
    
    // Navigate to parent, creating objects as needed
    // We need to rebuild the path from root each time since we can't hold pointers
    // to values inside the ConfigValue structure (they're returned by value)
    
    if (root_.getType() == ConfigValueType::Null) {
        root_ = ConfigValue();
    }
    
    // For simplicity, we'll use a recursive helper approach
    // This is not the most efficient but works correctly
    std::function<void(ConfigValue&, size_t)> setRecursive = 
        [&](ConfigValue& current, size_t depth) {
            if (depth == components.size() - 1) {
                // Last component - set the value
                current.objectSet(components[depth], value);
            } else {
                // Intermediate component - ensure it exists and recurse
                if (!current.isObject()) {
                    current = ConfigValue();
                }
                if (!current.objectHas(components[depth])) {
                    current.objectSet(components[depth], ConfigValue());
                }
                // Get the child and recurse
                ConfigValue child = current.objectGet(components[depth]);
                setRecursive(child, depth + 1);
                // Put the modified child back
                current.objectSet(components[depth], child);
            }
        };
    
    setRecursive(root_, 0);
}

bool ConfigService::has(const std::string& path) const {
    auto components = parsePath(path);
    ConfigValue current = root_;
    
    for (const auto& component : components) {
        if (!current.isObject() || !current.objectHas(component)) {
            return false;
        }
        current = current.objectGet(component);
    }
    
    return true;
}

void ConfigService::remove(const std::string& path) {
    // Simplified implementation - would need proper navigation
    LOG_W("ConfigService", "remove() not fully implemented yet");
}

void ConfigService::clear() {
    root_ = ConfigValue();
}

} // namespace jcode
