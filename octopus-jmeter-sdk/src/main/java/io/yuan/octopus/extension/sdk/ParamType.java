package io.yuan.octopus.extension.sdk;

/** Parameter and output types. JMeter passes text; the SDK converts it according to this type. */
public enum ParamType {
    STRING, INTEGER, NUMBER, BOOLEAN,
    /** A JSON object, passed and returned as JSON text. */
    OBJECT
}
